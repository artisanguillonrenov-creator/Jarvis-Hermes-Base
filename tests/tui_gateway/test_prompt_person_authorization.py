from __future__ import annotations

import json
import threading
import time
import types

import pytest

from agent.turn_authorization import (
    TurnAuthorization,
    current_fizko_authorization_header,
)
from tui_gateway import server as srv


def _authorization(raw, *, expires_at=None, principal_id="a" * 64):
    if raw is None:
        return TurnAuthorization.from_raw(None)
    return TurnAuthorization.from_raw(
        raw,
        expires_at=time.time() + 3600 if expires_at is None else expires_at,
        principal_id=principal_id,
    )


class _InlineThread:
    def __init__(self, target=None, daemon=None, args=(), kwargs=None):
        self._target, self._args, self._kwargs = target, args, kwargs or {}

    def start(self):
        if self._target is not None:
            self._target(*self._args, **self._kwargs)

    def is_alive(self):
        return False

    def join(self, timeout=None):
        return None


def _session(agent):
    return {
        "agent": agent,
        "session_key": "agent-session-key",
        "history": [],
        "history_lock": threading.Lock(),
        "history_version": 0,
        "running": False,
        "attached_images": [],
        "image_counter": 0,
        "cols": 80,
        "slash_worker": None,
        "show_reasoning": False,
        "tool_progress_mode": "all",
        "inflight_turn": None,
        "transport": None,
    }


def test_prompt_submit_rejects_malformed_token_after_popping_it():
    params = {
        "session_id": "missing",
        "text": "hello",
        "_fizko_person_access_token": "contains whitespace",
    }

    response = srv._methods["prompt.submit"]("r", params)

    assert response["error"]["code"] == 4004
    assert params == {"session_id": "missing", "text": "hello"}


def test_prompt_submit_rejects_person_token_without_expiry():
    response = srv._methods["prompt.submit"](
        "r",
        {
            "session_id": "missing",
            "text": "hello",
            "_fizko_person_access_token": "person-token",
        },
    )

    assert response["error"]["code"] == 4004
    assert "expiry is required" in response["error"]["message"]


def test_prompt_submit_rejects_expired_person_token():
    response = srv._methods["prompt.submit"](
        "r",
        {
            "session_id": "missing",
            "text": "hello",
            "_fizko_person_access_token": "person-token",
            "_fizko_person_access_token_expires_at": time.time() - 1,
            "_fizko_person_principal_id": "a" * 64,
        },
    )

    assert response["error"]["code"] == 4004
    assert "expired" in response["error"]["message"]


def test_person_authorized_submit_fails_closed_when_compute_isolation_is_required(monkeypatch):
    session = _session(types.SimpleNamespace())
    monkeypatch.setattr(srv, "_session_uses_compute_host", lambda *_args: True)
    srv._sessions["sid"] = session
    try:
        response = srv._methods["prompt.submit"](
            "r",
            {
                "session_id": "sid",
                "text": "hello",
                "_fizko_person_access_token": "person-token",
                "_fizko_person_access_token_expires_at": time.time() + 3600,
                "_fizko_person_principal_id": "a" * 64,
            },
        )
    finally:
        srv._sessions.pop("sid", None)

    assert response["error"]["code"] == 4126
    assert session["running"] is False
    assert "_active_turn_authorization" not in session


def test_queued_person_authorized_turn_does_not_bypass_compute_isolation(monkeypatch):
    holder = _authorization("queued-person")
    session = _session(types.SimpleNamespace())
    session["queued_prompt"] = {
        "text": "queued work",
        "transport": None,
        "turn_authorization": holder,
    }
    events = []
    monkeypatch.setattr(srv, "_session_uses_compute_host", lambda *_args: True)
    monkeypatch.setattr(srv, "_emit", lambda *args: events.append(args))

    assert srv._drain_queued_prompt("r", "sid", session) is True

    assert session["running"] is False
    assert session.get("queued_prompt") is None
    assert "_active_turn_authorization" not in session
    assert events and events[0][0] == "error"


def test_expired_queued_person_authorization_is_dropped_fail_closed(monkeypatch):
    holder = _authorization("expired-person", expires_at=time.time() - 1)
    session = _session(types.SimpleNamespace())
    session["queued_prompt"] = {
        "text": "queued work",
        "transport": None,
        "turn_authorization": holder,
    }
    events = []
    monkeypatch.setattr(srv, "_session_uses_compute_host", lambda *_args: False)
    monkeypatch.setattr(srv, "_emit", lambda *args: events.append(args))
    monkeypatch.setattr(
        srv,
        "_run_prompt_submit",
        lambda *_args, **_kwargs: pytest.fail("expired queued prompt was dispatched"),
    )

    assert srv._drain_queued_prompt("r", "sid", session) is True

    assert session["running"] is False
    assert session.get("queued_prompt") is None
    assert "_active_turn_authorization" not in session
    assert events and events[0][0] == "error"
    assert "expired" in events[0][2]["message"]


def test_expired_queued_head_does_not_block_later_static_prompt(monkeypatch):
    expired = _authorization("expired-person", expires_at=time.time() - 1)
    session = _session(types.SimpleNamespace())
    session["queued_prompt"] = {
        "text": "expired work", "transport": None, "turn_authorization": expired,
    }
    session["queued_prompts"] = [{"text": "ordinary work", "transport": None}]
    events = []
    dispatched = []
    monkeypatch.setattr(srv, "_session_uses_compute_host", lambda *_args: False)
    monkeypatch.setattr(srv, "_emit", lambda *args: events.append(args))
    monkeypatch.setattr(
        srv,
        "_run_prompt_submit",
        lambda _rid, _sid, _session, text, **kwargs: dispatched.append((text, kwargs)) or True,
    )

    assert srv._drain_queued_prompt("r", "sid", session) is True

    assert [text for text, _kwargs in dispatched] == ["ordinary work"]
    assert "turn_authorization" not in dispatched[0][1]
    assert any("expired" in args[2]["message"] for args in events)


def test_rejected_queue_batch_schedules_bounded_continuation(monkeypatch):
    expired = _authorization("expired-person", expires_at=time.time() - 1)
    rejected = {"text": "expired", "transport": None, "turn_authorization": expired}
    session = _session(types.SimpleNamespace())
    session["queued_prompt"] = dict(rejected)
    session["queued_prompts"] = [dict(rejected) for _ in range(32)] + [
        {"text": "ordinary", "transport": None}
    ]
    dispatched = []
    monkeypatch.setattr(srv, "_session_uses_compute_host", lambda *_args: False)
    monkeypatch.setattr(srv, "_emit", lambda *_args: None)
    monkeypatch.setattr(
        srv,
        "_run_prompt_submit",
        lambda _rid, _sid, _session, text, **kwargs: dispatched.append(text) or True,
    )

    class _ImmediateThread:
        def __init__(self, *, target, args, daemon):
            self.target = target
            self.args = args

        def start(self):
            self.target(*self.args)

    monkeypatch.setattr(srv.threading, "Thread", _ImmediateThread)

    assert srv._drain_queued_prompt("r", "sid", session) is True
    assert dispatched == ["ordinary"]
    assert session.get("queued_prompt") is None


def test_personal_post_turn_steer_keeps_blocked_descendant_marker(monkeypatch):
    session = _session(types.SimpleNamespace())
    blocked = TurnAuthorization.blocked()
    monkeypatch.setattr(srv, "_drain_queued_prompt", lambda *_args: True)

    srv._run_post_turn_followups(
        "r",
        "sid",
        session,
        {"pending_steer": "continue safely"},
        None,
        descendant_authorization=blocked,
    )

    queued = session["queued_prompt"]
    assert queued["turn_authorization"] is blocked
    assert queued["turn_authorization"].is_personal is True
    assert queued["turn_authorization"].has_token is False


def test_prompt_submit_pops_token_scopes_it_to_run_and_resets_without_leaks(monkeypatch, tmp_path):
    secret = "person-token-never-persist"
    seen = []
    events = []

    def run_conversation(user_message, **kwargs):
        seen.append((user_message, current_fizko_authorization_header(), kwargs))
        return {"final_response": "ok"}

    agent = types.SimpleNamespace(
        session_id="a",
        run_conversation=run_conversation,
        clear_interrupt=lambda: None,
    )
    session = _session(agent)
    params = {
        "session_id": "sid",
        "text": "hello",
        "_fizko_person_access_token": secret,
        "_fizko_person_access_token_expires_at": time.time() + 3600,
        "_fizko_person_principal_id": "a" * 64,
    }
    monkeypatch.setattr(srv.threading, "Thread", _InlineThread)
    monkeypatch.setattr(srv, "_emit", lambda *args: events.append(args))
    for name in (
        "_wire_callbacks",
        "_sync_agent_model_with_config",
        "_register_session_cwd",
        "_tts_stream_begin",
        "_sync_session_key_after_compress",
    ):
        monkeypatch.setattr(srv, name, lambda *a, **k: None)
    monkeypatch.setattr(srv, "_session_cwd", lambda _session: str(tmp_path))
    monkeypatch.setattr(srv, "_get_usage", lambda _agent: {})
    srv._sessions["sid"] = session
    try:
        response = srv._methods["prompt.submit"]("r", params)
    finally:
        srv._sessions.pop("sid", None)

    assert response["result"]["status"] == "streaming"
    assert params == {"session_id": "sid", "text": "hello"}
    assert seen[0][0:2] == ("hello", "Bearer " + secret)
    assert secret not in json.dumps(seen[0][2], default=repr)
    assert secret not in json.dumps(session, default=repr)
    assert secret not in json.dumps(events, default=repr)
    assert current_fizko_authorization_header() == ""


def test_different_person_cannot_redirect_or_interrupt_the_active_turn(monkeypatch):
    redirects = []
    interrupts = []
    active = _authorization("active-person")
    incoming = _authorization("incoming-person")
    agent = types.SimpleNamespace(
        _supports_active_turn_redirect=True,
        redirect=lambda text: redirects.append(text) or True,
        interrupt=lambda: interrupts.append(True),
    )
    session = _session(agent)
    session.update(running=True, _active_turn_authorization=active)
    monkeypatch.setattr(srv, "_load_busy_input_mode", lambda: "interrupt")
    monkeypatch.setattr(srv, "_interrupt_busy_session", lambda *args: interrupts.append(True))
    srv._sessions["sid"] = session
    try:
        response = srv._methods["prompt.submit"](
            "r",
            {
                "session_id": "sid",
                "text": "do not steer active",
                "_fizko_person_access_token": "incoming-person",
                "_fizko_person_access_token_expires_at": time.time() + 3600,
                "_fizko_person_principal_id": "b" * 64,
            },
        )
    finally:
        srv._sessions.pop("sid", None)

    assert response["result"] == {"status": "queued"}
    assert redirects == []
    assert interrupts == []
    queued = session["queued_prompt"]
    assert queued["turn_authorization"].same_credential(incoming)
    assert "incoming-person" not in repr(queued)


@pytest.mark.parametrize(
    ("mode", "active_raw", "incoming_raw"),
    [
        ("interrupt", "same-person", "same-person"),
        ("steer", "same-person", "same-person"),
        ("interrupt", None, "incoming-person"),
        ("steer", None, "incoming-person"),
    ],
)
def test_any_token_bearing_busy_submit_queues_without_live_agent_mutation(
    monkeypatch, mode, active_raw, incoming_raw,
):
    calls = []
    agent = types.SimpleNamespace(
        _supports_active_turn_redirect=True,
        redirect=lambda text: calls.append(("redirect", text)) or True,
        steer=lambda text: calls.append(("steer", text)) or True,
        interrupt=lambda: calls.append(("interrupt", None)),
    )
    session = _session(agent)
    session.update(
        running=True,
        _active_turn_authorization=_authorization(active_raw),
        _active_turn_route="inline",
    )
    monkeypatch.setattr(srv, "_load_busy_input_mode", lambda: mode)
    monkeypatch.setattr(
        srv,
        "_interrupt_busy_session",
        lambda *_args: calls.append(("hard-interrupt", None)),
    )

    response = srv._handle_busy_submit(
        "r", "sid", session, "wait your turn", None,
        turn_authorization=_authorization(incoming_raw),
    )

    assert response["result"] == {"status": "queued"}
    assert calls == []
    assert session["queued_prompt"]["text"] == "wait your turn"


def test_authorization_clears_when_turn_is_cancelled_before_agent_ready(monkeypatch):
    holder = _authorization("cancelled-person")
    session = _session(types.SimpleNamespace())
    session.update(running=True, _active_turn_authorization=holder, _active_turn_route="inline")
    monkeypatch.setattr(srv, "_wait_agent_for_prompt", lambda *args: {"error": {"message": "cancelled"}})
    monkeypatch.setattr(srv, "_emit_terminal_turn_error", lambda *args, **kwargs: None)
    monkeypatch.setattr(srv, "_emit", lambda *args, **kwargs: None)
    monkeypatch.setattr(srv, "_session_info", lambda *args: {})

    srv._run_after_agent_ready("r", "sid", session, "hello", None, None, None, holder)

    assert session["running"] is False
    assert "_active_turn_authorization" not in session
    assert "_active_turn_route" not in session
    assert current_fizko_authorization_header() == ""


def test_missing_queue_authorization_never_merges_into_token_envelope():
    holder = _authorization("queued-person")
    session = _session(types.SimpleNamespace())
    session["queued_prompt"] = {
        "text": "personal work",
        "transport": None,
        "turn_authorization": holder,
    }

    srv._enqueue_prompt(session, "internal follow-up", None)

    assert session["queued_prompt"]["text"] == "personal work"
    assert session["queued_prompt"]["turn_authorization"] is holder
    assert session["queued_prompts"] == [
        {"text": "internal follow-up", "transport": None}
    ]


def test_queued_prompts_keep_distinct_person_authorizations(monkeypatch):
    active = _authorization("person-a")
    session = _session(types.SimpleNamespace())
    session.update(running=True, _active_turn_authorization=active)
    monkeypatch.setattr(srv, "_interrupt_busy_session", lambda *args: None)
    monkeypatch.setattr(srv, "_session_uses_compute_host", lambda *args: False)
    dispatched = []
    monkeypatch.setattr(
        srv,
        "_run_prompt_submit",
        lambda rid, sid, st, text, **kwargs: (
            dispatched.append((text, kwargs["turn_authorization"])) or True
        ),
    )
    srv._sessions["sid"] = session
    try:
        for person in ("person-b", "person-c"):
            response = srv._methods["prompt.submit"](
                "r",
                {
                    "session_id": "sid",
                    "text": f"from {person}",
                    "queued": True,
                    "_fizko_person_access_token": person,
                    "_fizko_person_access_token_expires_at": time.time() + 3600,
                    "_fizko_person_principal_id": ("b" if person == "person-b" else "c") * 64,
                },
            )
            assert response["result"] == {"status": "queued"}
        assert session["queued_prompt"]["text"] == "from person-b"
        assert [item["text"] for item in session["queued_prompts"]] == ["from person-c"]

        for _ in range(2):
            session["running"] = False
            session.pop("_active_turn_authorization", None)
            assert srv._drain_queued_prompt("d", "sid", session)
    finally:
        srv._sessions.pop("sid", None)

    assert [text for text, _holder in dispatched] == ["from person-b", "from person-c"]
    assert dispatched[0][1].same_credential(_authorization("person-b"))
    assert dispatched[1][1].same_credential(_authorization("person-c"))


def test_same_text_from_different_person_is_not_deduplicated(monkeypatch):
    active = _authorization("person-a")
    session = _session(types.SimpleNamespace())
    session.update(
        running=True,
        _active_turn_authorization=active,
        inflight_turn={"user": "same words", "assistant": "", "streaming": True, "error": ""},
    )
    monkeypatch.setattr(srv, "_interrupt_busy_session", lambda *args: None)
    srv._sessions["sid"] = session
    try:
        response = srv._methods["prompt.submit"](
            "r",
            {
                "session_id": "sid",
                "text": "same words",
                "queued": True,
                "_fizko_person_access_token": "person-b",
                "_fizko_person_access_token_expires_at": time.time() + 3600,
                "_fizko_person_principal_id": "b" * 64,
            },
        )
    finally:
        srv._sessions.pop("sid", None)

    assert response["result"] == {"status": "queued"}
    assert session["queued_prompt"]["text"] == "same words"


def test_authorization_resets_after_agent_error(monkeypatch, tmp_path):
    holder = _authorization("error-person")

    def fail(*args, **kwargs):
        assert current_fizko_authorization_header() == "Bearer error-person"
        raise RuntimeError("boom")

    agent = types.SimpleNamespace(session_id="a", run_conversation=fail, clear_interrupt=lambda: None)
    session = _session(agent)
    session.update(running=True, _active_turn_authorization=holder)
    monkeypatch.setattr(srv.threading, "Thread", _InlineThread)
    monkeypatch.setattr(srv, "_emit", lambda *args: None)
    for name in (
        "_wire_callbacks",
        "_sync_agent_model_with_config",
        "_register_session_cwd",
        "_tts_stream_begin",
        "_sync_session_key_after_compress",
    ):
        monkeypatch.setattr(srv, name, lambda *args, **kwargs: None)
    monkeypatch.setattr(srv, "_session_cwd", lambda _session: str(tmp_path))
    monkeypatch.setattr(srv, "_get_usage", lambda _agent: {})

    assert srv._run_prompt_submit("r", "sid", session, "hello", turn_authorization=holder)

    assert current_fizko_authorization_header() == ""
    assert "_active_turn_authorization" not in session


def test_queued_personal_admission_is_atomic_against_concurrent_submit(monkeypatch):
    queued = _authorization("queued-person")
    session = _session(types.SimpleNamespace())
    session["queued_prompt"] = {
        "text": "queued work",
        "transport": None,
        "turn_authorization": queued,
    }
    route_entered = threading.Event()
    release_route = threading.Event()
    busy_snapshots = []

    def route(_session, *_args):
        if threading.current_thread().name == "drain-personal":
            route_entered.set()
            assert release_route.wait(2)
            return False
        return False

    def busy(*_args, **_kwargs):
        with session["history_lock"]:
            busy_snapshots.append((
                session.get("running"),
                session.get("_active_turn_route"),
                session.get("_active_turn_authorization"),
            ))
        return {"result": {"status": "queued"}}

    monkeypatch.setattr(srv, "_session_uses_compute_host", route)
    monkeypatch.setattr(srv, "_run_prompt_submit", lambda *_args, **_kwargs: True)
    monkeypatch.setattr(srv, "_ensure_active_session_slot", lambda *_args: None)
    monkeypatch.setattr(srv, "_legacy_group_fence_error", lambda *_args: None)
    monkeypatch.setattr(srv, "_handle_busy_submit", busy)
    monkeypatch.setattr(srv, "_load_dashboard_process_isolation_config", lambda: {})
    srv._sessions["sid"] = session
    try:
        drain = threading.Thread(
            target=lambda: srv._drain_queued_prompt("drain", "sid", session),
            name="drain-personal",
        )
        drain.start()
        assert route_entered.wait(1)
        submit = threading.Thread(
            target=lambda: srv._methods["prompt.submit"](
                "submit",
                {
                    "session_id": "sid",
                    "text": "same person correction",
                    "_fizko_person_access_token": "queued-person",
                    "_fizko_person_access_token_expires_at": time.time() + 3600,
                    "_fizko_person_principal_id": "a" * 64,
                },
            ),
            name="concurrent-submit",
        )
        submit.start()
        time.sleep(0.05)
        assert busy_snapshots == []
        release_route.set()
        drain.join(2)
        submit.join(2)
    finally:
        release_route.set()
        srv._sessions.pop("sid", None)

    assert not drain.is_alive()
    assert not submit.is_alive()
    assert len(busy_snapshots) == 1
    running, route_name, authorization = busy_snapshots[0]
    assert running is True
    assert route_name == "inline"
    assert authorization.same_credential(queued)


def test_failed_queued_inline_dispatch_restores_prompt_and_clears_authorization(monkeypatch):
    holder = _authorization("queued-person")
    queued = {"text": "try later", "transport": None, "turn_authorization": holder}
    session = _session(types.SimpleNamespace())
    session["queued_prompt"] = queued
    monkeypatch.setattr(srv, "_session_uses_compute_host", lambda *_args: False)
    monkeypatch.setattr(srv, "_run_prompt_submit", lambda *_args, **_kwargs: False)

    assert srv._drain_queued_prompt("drain", "sid", session) is True

    assert session["running"] is False
    assert session["queued_prompt"] is queued
    assert "_active_turn_authorization" not in session
    assert "_active_turn_route" not in session


def test_failed_run_prompt_admission_clears_authorization_and_route(monkeypatch):
    holder = _authorization("refused-person")
    session = _session(types.SimpleNamespace())
    session.update(running=True, _active_turn_authorization=holder, _active_turn_route="inline")
    monkeypatch.setattr(srv, "_ensure_active_session_slot", lambda *_args: RuntimeError("owned elsewhere"))
    monkeypatch.setattr(srv, "_emit", lambda *_args: None)

    assert srv._run_prompt_submit(
        "r", "sid", session, "hello", turn_authorization=holder
    ) is False

    assert session["running"] is False
    assert "_active_turn_authorization" not in session
    assert "_active_turn_route" not in session


def test_interrupt_clears_active_authorization_and_uses_forced_inline_route(monkeypatch):
    holder = _authorization("active-person")
    interrupted = []
    compute_interrupts = []
    agent = types.SimpleNamespace(interrupt=lambda: interrupted.append(True))
    session = _session(agent)
    session.update(
        running=True,
        _active_turn_authorization=holder,
        _active_turn_route="inline",
        _run_thread=None,
    )
    monkeypatch.setattr(srv, "_session_uses_compute_host", lambda *_args: True)
    monkeypatch.setattr(
        srv,
        "_get_compute_host_supervisor",
        lambda: types.SimpleNamespace(interrupt=lambda *_a, **_k: compute_interrupts.append(True)),
    )
    monkeypatch.setattr(srv, "_clear_pending", lambda *_args: None)

    assert srv._interrupt_session_turn("sid", session) is False

    assert interrupted == [True]
    assert compute_interrupts == []
    assert "_active_turn_authorization" not in session
    assert "_active_turn_route" not in session


def test_reset_clears_active_authorization_and_route(monkeypatch):
    holder = _authorization("active-person")
    new_agent = types.SimpleNamespace()
    session = _session(types.SimpleNamespace())
    session.update(_active_turn_authorization=holder, _active_turn_route="inline")
    monkeypatch.setattr(srv, "_set_session_context", lambda *_args: [])
    monkeypatch.setattr(srv, "_clear_session_context", lambda *_args: None)
    monkeypatch.setattr(srv, "_rebuild_session_agent", lambda *_args, **_kwargs: new_agent)
    monkeypatch.setattr(srv, "_session_source", lambda *_args: "tui")
    monkeypatch.setattr(srv, "_context_cwd_is_launch_artifact", lambda *_args: False)
    monkeypatch.setattr(srv, "_session_info", lambda *_args: {})
    monkeypatch.setattr(srv, "_emit", lambda *_args: None)
    monkeypatch.setattr(srv, "_restart_slash_worker", lambda *_args: None)

    srv._reset_session_agent("sid", session)

    assert "_active_turn_authorization" not in session
    assert "_active_turn_route" not in session


def _install_token_bearing_direct_rpc_session(monkeypatch):
    calls = {"steer": [], "redirect": [], "interrupt": []}
    agent = types.SimpleNamespace(
        _supports_active_turn_redirect=True,
        steer=lambda text: calls["steer"].append(text) or True,
        redirect=lambda text: calls["redirect"].append(text) or True,
        interrupt=lambda: calls["interrupt"].append(True),
    )
    session = _session(agent)
    session.update(
        running=True,
        _active_turn_authorization=_authorization("active-person"),
        _active_turn_route="inline",
        _run_thread=None,
    )
    monkeypatch.setattr(srv, "_tts_stream_stop", lambda: None)
    monkeypatch.setattr(srv, "_session_uses_compute_host", lambda *_args: False)
    monkeypatch.setattr(srv, "_clear_pending", lambda *_args: None)
    srv._sessions["sid"] = session
    return session, calls


def test_direct_session_steer_fails_closed_for_personal_turn(monkeypatch):
    _session_record, calls = _install_token_bearing_direct_rpc_session(monkeypatch)
    try:
        response = srv._methods["session.steer"]("r", {"session_id": "sid", "text": "change"})
    finally:
        srv._sessions.pop("sid", None)

    assert response["error"]["code"] == 4125
    assert calls["steer"] == []


def test_direct_session_redirect_fails_closed_for_personal_turn(monkeypatch):
    _session_record, calls = _install_token_bearing_direct_rpc_session(monkeypatch)
    try:
        response = srv._methods["session.redirect"]("r", {"session_id": "sid", "text": "change"})
    finally:
        srv._sessions.pop("sid", None)

    assert response["error"]["code"] == 4125
    assert calls["redirect"] == []


def test_direct_session_interrupt_fails_closed_for_personal_turn(monkeypatch):
    session, calls = _install_token_bearing_direct_rpc_session(monkeypatch)
    try:
        response = srv._methods["session.interrupt"]("r", {"session_id": "sid"})
    finally:
        srv._sessions.pop("sid", None)

    assert response["error"]["code"] == 4125
    assert calls["interrupt"] == []
    assert session["running"] is True
    assert "_active_turn_authorization" in session


def test_direct_session_interrupt_accepts_matching_person_authorization(monkeypatch):
    session, calls = _install_token_bearing_direct_rpc_session(monkeypatch)
    try:
        response = srv._methods["session.interrupt"](
            "r",
            {
                "session_id": "sid",
                "_fizko_person_access_token": "active-person",
                "_fizko_person_access_token_expires_at": time.time() + 3600,
                "_fizko_person_principal_id": "a" * 64,
            },
        )
    finally:
        srv._sessions.pop("sid", None)

    assert response["result"]["status"] == "interrupted"
    assert calls["interrupt"] == [True]
    assert session["running"] is False
    assert "_active_turn_authorization" not in session


def test_reconnected_person_can_interrupt_active_turn_with_rotated_bearer(monkeypatch):
    session, calls = _install_token_bearing_direct_rpc_session(monkeypatch)
    session["_active_turn_authorization"] = _authorization(
        "person-token-t1", principal_id="a" * 64
    )
    try:
        response = srv._methods["session.interrupt"](
            "r",
            {
                "session_id": "sid",
                "_fizko_person_access_token": "person-token-t2",
                "_fizko_person_access_token_expires_at": time.time() + 3600,
                "_fizko_person_principal_id": "a" * 64,
            },
        )
    finally:
        srv._sessions.pop("sid", None)

    assert response["result"]["status"] == "interrupted"
    assert calls["interrupt"] == [True]
    assert session["running"] is False


def test_different_person_principal_cannot_interrupt_active_turn(monkeypatch):
    session, calls = _install_token_bearing_direct_rpc_session(monkeypatch)
    session["_active_turn_authorization"] = _authorization(
        "person-token-t1", principal_id="a" * 64
    )
    try:
        response = srv._methods["session.interrupt"](
            "r",
            {
                "session_id": "sid",
                "_fizko_person_access_token": "person-token-t2",
                "_fizko_person_access_token_expires_at": time.time() + 3600,
                "_fizko_person_principal_id": "b" * 64,
            },
        )
    finally:
        srv._sessions.pop("sid", None)

    assert response["error"]["code"] == 4125
    assert calls["interrupt"] == []
    assert session["running"] is True


def test_reconnected_person_queues_rotated_bearer_behind_owned_active_turn(monkeypatch):
    session, calls = _install_token_bearing_direct_rpc_session(monkeypatch)
    session["_active_turn_authorization"] = _authorization(
        "person-token-t1", principal_id="a" * 64
    )
    monkeypatch.setattr(srv, "_load_busy_input_mode", lambda: "queue")
    try:
        params = {
            "session_id": "sid",
            "text": "run after reconnect",
            "_fizko_person_access_token": "person-token-t2",
            "_fizko_person_access_token_expires_at": time.time() + 3600,
            "_fizko_person_principal_id": "a" * 64,
        }
        response = srv._methods["prompt.submit"]("r", params)
    finally:
        srv._sessions.pop("sid", None)

    assert response["result"] == {"status": "queued"}
    queued = session["queued_prompt"]["turn_authorization"]
    assert queued.same_principal(session["_active_turn_authorization"])
    assert not queued.same_credential(session["_active_turn_authorization"])
    assert params == {"session_id": "sid", "text": "run after reconnect"}
    assert calls == {"steer": [], "redirect": [], "interrupt": []}


def test_interrupt_keeps_personal_fence_until_live_worker_settles(monkeypatch):
    session, calls = _install_token_bearing_direct_rpc_session(monkeypatch)
    session["_run_thread"] = types.SimpleNamespace(is_alive=lambda: True)
    holder = session["_active_turn_authorization"]
    try:
        response = srv._methods["session.interrupt"](
            "r",
            {
                "session_id": "sid",
                "_fizko_person_access_token": "active-person",
                "_fizko_person_access_token_expires_at": time.time() + 3600,
                "_fizko_person_principal_id": "a" * 64,
            },
        )
        assert response["result"]["status"] == "interrupted"
        assert session["running"] is True
        assert session["_active_turn_authorization"] is holder
        assert srv._direct_personal_turn_mutation_error("next", session)["error"]["code"] == 4125

        with session["history_lock"]:
            session["running"] = False
            assert srv._clear_active_turn_state(session, holder)
        assert "_active_turn_authorization" not in session
    finally:
        srv._sessions.pop("sid", None)

    assert calls["interrupt"] == [True]


def test_expired_person_authorization_cannot_interrupt(monkeypatch):
    session, calls = _install_token_bearing_direct_rpc_session(monkeypatch)
    try:
        response = srv._methods["session.interrupt"](
            "r",
            {
                "session_id": "sid",
                "_fizko_person_access_token": "active-person",
                "_fizko_person_access_token_expires_at": time.time() - 1,
                "_fizko_person_principal_id": "a" * 64,
            },
        )
    finally:
        srv._sessions.pop("sid", None)

    assert response["error"]["code"] == 4125
    assert calls["interrupt"] == []
    assert session["running"] is True


def test_direct_session_interrupt_rejects_different_person_authorization(monkeypatch):
    session, calls = _install_token_bearing_direct_rpc_session(monkeypatch)
    try:
        response = srv._methods["session.interrupt"](
            "r",
            {
                "session_id": "sid",
                "_fizko_person_access_token": "different-person",
                "_fizko_person_access_token_expires_at": time.time() + 3600,
                "_fizko_person_principal_id": "b" * 64,
            },
        )
    finally:
        srv._sessions.pop("sid", None)

    assert response["error"]["code"] == 4125
    assert calls["interrupt"] == []
    assert session["running"] is True
