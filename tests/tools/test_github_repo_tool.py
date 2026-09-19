"""Tests for the GitHub REST API tool (no local git/gh dependency)."""

import base64
import json
from unittest.mock import MagicMock, patch

from tools.github_repo_tool import check_github_requirements, github_repo
from tools.registry import registry


def _mock_response(status_code=200, json_data=None):
    resp = MagicMock()
    resp.status_code = status_code
    resp.json.return_value = json_data or {}
    return resp


class TestAvailabilityGating:
    def test_unavailable_without_token(self):
        with patch("tools.github_repo_tool.get_secret", return_value=None):
            assert check_github_requirements() is False

    def test_available_with_token(self):
        with patch("tools.github_repo_tool.get_secret", return_value="ghp_fake"):
            assert check_github_requirements() is True


class TestRegistration:
    def test_registered_under_github_toolset(self):
        entry = registry._tools.get("github_repo")
        assert entry is not None
        assert entry.toolset == "github"
        assert "GITHUB_TOKEN" in entry.requires_env


class TestActionValidation:
    def test_unknown_action_is_an_error(self):
        result = json.loads(github_repo(action="not_a_real_action", repo="octocat/hello-world"))
        assert "error" in result
        assert "Unknown action" in result["error"]

    def test_missing_repo_slash_is_an_error(self):
        result = json.loads(github_repo(action="get_file", repo="not-a-valid-repo-slug", path="x.py"))
        assert "error" in result


class TestWriteFileCreateVsUpdate:
    """The write path must diff create (no sha) from update (sha required) correctly —
    sending a sha on a create 422s, and omitting it on an update overwrites blind."""

    def test_new_file_omits_sha(self):
        get_resp = _mock_response(status_code=404)
        put_resp = _mock_response(status_code=200, json_data={
            "content": {"path": "new.py", "sha": "abc123"},
            "commit": {"sha": "def456", "html_url": "https://github.com/x/y/commit/def456"},
        })
        client = MagicMock()
        client.get.return_value = get_resp
        client.put.return_value = put_resp
        client.__enter__.return_value = client
        client.__exit__.return_value = False

        with patch("tools.github_repo_tool.httpx.Client", return_value=client), \
             patch("tools.github_repo_tool.get_secret", return_value="ghp_fake"):
            result = json.loads(github_repo(
                action="write_file", repo="octocat/hello-world", path="new.py",
                content="print('hi')", message="add new.py"))

        assert result["created"] is True
        sent_payload = client.put.call_args.kwargs["json"]
        assert "sha" not in sent_payload
        assert base64.b64decode(sent_payload["content"]).decode() == "print('hi')"

    def test_existing_file_includes_sha(self):
        get_resp = _mock_response(status_code=200, json_data={"sha": "existing-sha"})
        put_resp = _mock_response(status_code=200, json_data={
            "content": {"path": "old.py", "sha": "new-sha"},
            "commit": {"sha": "commit-sha", "html_url": "https://github.com/x/y/commit/commit-sha"},
        })
        client = MagicMock()
        client.get.return_value = get_resp
        client.put.return_value = put_resp
        client.__enter__.return_value = client
        client.__exit__.return_value = False

        with patch("tools.github_repo_tool.httpx.Client", return_value=client), \
             patch("tools.github_repo_tool.get_secret", return_value="ghp_fake"):
            result = json.loads(github_repo(
                action="write_file", repo="octocat/hello-world", path="old.py",
                content="updated", message="update old.py"))

        assert result["created"] is False
        sent_payload = client.put.call_args.kwargs["json"]
        assert sent_payload["sha"] == "existing-sha"
