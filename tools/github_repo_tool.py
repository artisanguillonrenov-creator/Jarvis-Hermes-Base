"""GitHub repository access over the REST API (`httpx`, no `git`/`gh` binary required).

Built for platforms with no shell git/gh (this app's Android/Chaquopy build has neither —
`tools/AGENTS.md` backends section), but works anywhere GITHUB_TOKEN is set. One
action-oriented `github_repo` tool (schema/context bloat avoided), mirroring the
`cronjob_manage`/`kanban_manage` shape.
"""

import base64
import json
from typing import Optional

import httpx

from agent.secret_scope import get_secret
from tools.registry import registry, tool_error, tool_result

_API_BASE = "https://api.github.com"
_API_VERSION = "2022-11-28"
_TIMEOUT = 30.0


def check_github_requirements() -> bool:
    return bool(get_secret("GITHUB_TOKEN"))


def _headers() -> dict:
    return {
        "Authorization": f"Bearer {get_secret('GITHUB_TOKEN')}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": _API_VERSION,
    }


def _api_error(resp: httpx.Response) -> str:
    try:
        detail = resp.json().get("message", resp.text)
    except Exception:
        detail = resp.text
    return tool_error(f"GitHub API error {resp.status_code}: {detail}"[:2000], status=resp.status_code)


def _contents_url(repo: str, path: str) -> str:
    return f"{_API_BASE}/repos/{repo}/contents/{path.lstrip('/')}"


def _get_file_sha(client: httpx.Client, repo: str, path: str, branch: Optional[str]) -> Optional[str]:
    """Current blob sha for an existing file on ``branch``, or None if it doesn't exist there."""
    params = {"ref": branch} if branch else {}
    resp = client.get(_contents_url(repo, path), params=params)
    if resp.status_code == 404:
        return None
    if resp.status_code >= 400:
        return None
    data = resp.json()
    return data.get("sha") if isinstance(data, dict) else None


def _do_get_file(client: httpx.Client, repo: str, path: str, ref: Optional[str]) -> str:
    params = {"ref": ref} if ref else {}
    resp = client.get(_contents_url(repo, path), params=params)
    if resp.status_code >= 400:
        return _api_error(resp)
    data = resp.json()
    if isinstance(data, list):
        return tool_error(f"'{path}' is a directory, not a file — use action=list_dir")
    if data.get("encoding") == "base64":
        raw = base64.b64decode(data["content"])
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            return tool_result(path=data.get("path"), sha=data.get("sha"), size=data.get("size"),
                                binary=True, error="File is binary; not decoded as text.")
        return tool_result(path=data.get("path"), sha=data.get("sha"), size=data.get("size"), content=text)
    return tool_result(path=data.get("path"), sha=data.get("sha"), size=data.get("size"),
                        content=data.get("content"))


def _do_list_dir(client: httpx.Client, repo: str, path: str, ref: Optional[str]) -> str:
    params = {"ref": ref} if ref else {}
    resp = client.get(_contents_url(repo, path or ""), params=params)
    if resp.status_code >= 400:
        return _api_error(resp)
    data = resp.json()
    if not isinstance(data, list):
        return tool_error(f"'{path}' is a file, not a directory — use action=get_file")
    entries = [{"name": e.get("name"), "path": e.get("path"), "type": e.get("type"), "size": e.get("size")}
               for e in data]
    return tool_result(path=path or "/", entries=entries)


def _do_write_file(client: httpx.Client, repo: str, path: str, content: Optional[str],
                    message: Optional[str], branch: Optional[str]) -> str:
    if content is None:
        return tool_error("content is required for action=write_file")
    if not message:
        return tool_error("message (commit message) is required for action=write_file")
    sha = _get_file_sha(client, repo, path, branch)
    payload = {"message": message, "content": base64.b64encode(content.encode("utf-8")).decode("ascii")}
    if branch:
        payload["branch"] = branch
    if sha:
        payload["sha"] = sha
    resp = client.put(_contents_url(repo, path), json=payload)
    if resp.status_code >= 400:
        return _api_error(resp)
    data = resp.json()
    commit = data.get("commit", {})
    return tool_result(path=data.get("content", {}).get("path"), sha=data.get("content", {}).get("sha"),
                        commit_sha=commit.get("sha"), commit_url=commit.get("html_url"),
                        created=sha is None)


def _do_delete_file(client: httpx.Client, repo: str, path: str, message: Optional[str],
                     branch: Optional[str]) -> str:
    if not message:
        return tool_error("message (commit message) is required for action=delete_file")
    sha = _get_file_sha(client, repo, path, branch)
    if sha is None:
        return tool_error(f"'{path}' not found on {branch or 'the default branch'} — nothing to delete")
    payload = {"message": message, "sha": sha}
    if branch:
        payload["branch"] = branch
    resp = client.request("DELETE", _contents_url(repo, path), json=payload)
    if resp.status_code >= 400:
        return _api_error(resp)
    commit = resp.json().get("commit", {})
    return tool_result(deleted=path, commit_sha=commit.get("sha"), commit_url=commit.get("html_url"))


def _do_create_branch(client: httpx.Client, repo: str, branch: Optional[str], base_branch: Optional[str]) -> str:
    if not branch:
        return tool_error("branch (the new branch name) is required for action=create_branch")
    base = base_branch or "main"
    resp = client.get(f"{_API_BASE}/repos/{repo}/git/ref/heads/{base}")
    if resp.status_code >= 400:
        return _api_error(resp)
    base_sha = resp.json().get("object", {}).get("sha")
    resp = client.post(f"{_API_BASE}/repos/{repo}/git/refs",
                        json={"ref": f"refs/heads/{branch}", "sha": base_sha})
    if resp.status_code >= 400:
        return _api_error(resp)
    return tool_result(branch=branch, base_branch=base, sha=base_sha)


def _do_create_pull_request(client: httpx.Client, repo: str, title: Optional[str], body: Optional[str],
                             head: Optional[str], base: Optional[str]) -> str:
    if not title or not head or not base:
        return tool_error("title, head (source branch), and base (target branch) are all required "
                           "for action=create_pull_request")
    resp = client.post(f"{_API_BASE}/repos/{repo}/pulls",
                        json={"title": title, "body": body or "", "head": head, "base": base})
    if resp.status_code >= 400:
        return _api_error(resp)
    data = resp.json()
    return tool_result(number=data.get("number"), url=data.get("html_url"), state=data.get("state"))


_ACTIONS = {
    "get_file": lambda c, a: _do_get_file(c, a["repo"], a["path"], a.get("ref")),
    "list_dir": lambda c, a: _do_list_dir(c, a["repo"], a.get("path", ""), a.get("ref")),
    "write_file": lambda c, a: _do_write_file(c, a["repo"], a["path"], a.get("content"), a.get("message"), a.get("branch")),
    "delete_file": lambda c, a: _do_delete_file(c, a["repo"], a["path"], a.get("message"), a.get("branch")),
    "create_branch": lambda c, a: _do_create_branch(c, a["repo"], a.get("branch"), a.get("base_branch")),
    "create_pull_request": lambda c, a: _do_create_pull_request(c, a["repo"], a.get("title"), a.get("body"), a.get("head"), a.get("base")),
}


def github_repo(action: str, repo: str = "", path: Optional[str] = None, ref: Optional[str] = None,
                 branch: Optional[str] = None, base_branch: Optional[str] = None,
                 content: Optional[str] = None, message: Optional[str] = None,
                 title: Optional[str] = None, body: Optional[str] = None,
                 head: Optional[str] = None, base: Optional[str] = None,
                 task_id: str = None) -> str:
    """Read/write files and open PRs on a GitHub repo over the REST API (no local git needed)."""
    normalized = (action or "").strip().lower()
    handler = _ACTIONS.get(normalized)
    if handler is None:
        return tool_error(f"Unknown action '{action}'. Valid actions: {', '.join(_ACTIONS)}")
    if not repo or "/" not in repo:
        return tool_error("repo is required, formatted 'owner/name' (e.g. 'octocat/hello-world')")
    args = dict(path=path, ref=ref, branch=branch, base_branch=base_branch, content=content,
                message=message, title=title, body=body, head=head, base=base, repo=repo)
    try:
        with httpx.Client(headers=_headers(), timeout=_TIMEOUT) as client:
            return handler(client, args)
    except httpx.HTTPError as e:
        return tool_error(f"GitHub request failed: {e}")


_SCHEMA = {
    "name": "github_repo",
    "description": (
        "Read and modify files in a GitHub repository and open pull requests, over GitHub's REST "
        "API — no local git/gh needed, so this works even where a shell git binary isn't available. "
        "action='get_file' reads a file's text content; 'list_dir' lists a directory; 'write_file' "
        "creates or updates a file with a new commit (auto-detects create vs. update); 'delete_file' "
        "removes a file; 'create_branch' branches off base_branch; 'create_pull_request' opens a PR "
        "from head into base. Always list_dir/get_file first to see current state before writing — "
        "never guess a file's existing content or sha."
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "action": {"type": "string",
                       "description": "One of: get_file, list_dir, write_file, delete_file, create_branch, create_pull_request."},
            "repo": {"type": "string", "description": "Repository as 'owner/name', e.g. 'octocat/hello-world'."},
            "path": {"type": "string", "description": "File or directory path within the repo. Required for get_file/write_file/delete_file; omit or '' for list_dir at the repo root."},
            "ref": {"type": "string", "description": "Branch, tag, or commit sha to read from (get_file/list_dir). Omit for the repo's default branch."},
            "branch": {"type": "string", "description": "For write_file/delete_file: the branch to commit to (omit for the default branch). For create_branch: the NEW branch name to create."},
            "base_branch": {"type": "string", "description": "create_branch only: the existing branch to branch off of. Defaults to 'main'."},
            "content": {"type": "string", "description": "write_file only: the full new text content of the file (UTF-8). Required."},
            "message": {"type": "string", "description": "write_file/delete_file only: the commit message. Required."},
            "title": {"type": "string", "description": "create_pull_request only: PR title. Required."},
            "body": {"type": "string", "description": "create_pull_request only: PR description (Markdown)."},
            "head": {"type": "string", "description": "create_pull_request only: source branch containing the changes. Required."},
            "base": {"type": "string", "description": "create_pull_request only: target branch the PR merges into. Required."},
        },
        "required": ["action", "repo"],
    },
}


def _handler(args, **kw):
    return github_repo(
        action=args.get("action", ""), repo=args.get("repo", ""), path=args.get("path"),
        ref=args.get("ref"), branch=args.get("branch"), base_branch=args.get("base_branch"),
        content=args.get("content"), message=args.get("message"), title=args.get("title"),
        body=args.get("body"), head=args.get("head"), base=args.get("base"),
        task_id=kw.get("task_id"))


registry.register(
    name="github_repo",
    toolset="github",
    schema=_SCHEMA,
    handler=_handler,
    check_fn=check_github_requirements,
    requires_env=["GITHUB_TOKEN"],
    emoji="🐙",
)
