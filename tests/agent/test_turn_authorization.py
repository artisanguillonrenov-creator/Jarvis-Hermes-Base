from __future__ import annotations

import json
import pickle
import threading
from types import SimpleNamespace

import pytest


def test_turn_authorization_holder_is_opaque_redacted_and_nonserializable():
    from agent.turn_authorization import TurnAuthorization

    token = "person-token.sentinel-123"
    holder = TurnAuthorization.from_raw(token)

    assert token not in repr(holder)
    assert token not in str(holder)
    with pytest.raises(TypeError):
        pickle.dumps(holder)
    with pytest.raises(TypeError):
        json.dumps(holder)


def test_expired_person_authorization_remains_personal_but_has_no_header():
    from agent.turn_authorization import (
        TurnAuthorization,
        current_fizko_authorization_state,
        reset_current_turn_authorization,
        set_current_turn_authorization,
    )

    holder = TurnAuthorization.from_raw("expired-person", expires_at=1.0)
    token = set_current_turn_authorization(holder)
    try:
        assert holder.has_token is True
        assert holder.is_expired is True
        assert current_fizko_authorization_state() == (True, "")
    finally:
        reset_current_turn_authorization(token)


def test_real_delegate_child_worker_does_not_inherit_parent_turn_authorization(monkeypatch):
    from agent.turn_authorization import (
        TurnAuthorization,
        current_fizko_authorization_header,
        reset_current_turn_authorization,
        set_current_turn_authorization,
    )
    from tools.delegate_tool_child_run import _ChildRun

    seen = []
    child = SimpleNamespace(
        session_id="child-session",
        run_conversation=lambda **_kwargs: seen.append(current_fizko_authorization_header())
        or {"final_response": "done", "completed": True},
    )
    run = _ChildRun(child, SimpleNamespace(), 0, "work", None, None)
    monkeypatch.setattr("tools.delegate_tool._get_child_timeout", lambda: None)

    token = set_current_turn_authorization(TurnAuthorization.from_raw("person-token"))
    try:
        result, error, deferred = run.await_child()
        assert current_fizko_authorization_header() == "Bearer person-token"
    finally:
        reset_current_turn_authorization(token)

    assert result == {"final_response": "done", "completed": True}
    assert error is None
    assert deferred is False
    assert seen == [""]


def test_detached_process_reader_does_not_retain_parent_turn_authorization(monkeypatch, tmp_path):
    from agent.turn_authorization import (
        TurnAuthorization,
        current_fizko_authorization_header,
        reset_current_turn_authorization,
        set_current_turn_authorization,
    )
    from tools.process_registry import ProcessRegistry

    registry = ProcessRegistry()
    monkeypatch.setattr(registry, "_write_checkpoint", lambda: None)
    monkeypatch.setattr(registry, "_prune_if_needed", lambda: None)
    session = registry._new_session("noop", "task", "task", "session", str(tmp_path))
    observed = []
    finished = threading.Event()

    def reader(_session):
        observed.append(current_fizko_authorization_header())
        finished.set()

    token = set_current_turn_authorization(TurnAuthorization.from_raw("detached-parent-token"))
    try:
        registry._track_started(session, reader, "test-detached-reader")
        assert finished.wait(timeout=2)
        assert current_fizko_authorization_header() == "Bearer detached-parent-token"
    finally:
        reset_current_turn_authorization(token)

    assert session._reader_thread is not None
    session._reader_thread.join(timeout=2)
    assert observed == [""]
