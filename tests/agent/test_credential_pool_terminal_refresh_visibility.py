"""A terminally rejected OAuth refresh token must leave a trace at the default log level.

The pool quarantines a dead ``openai-codex`` / ``xai-oauth`` / ``nous`` refresh token (clears the
stored tokens, drops the seeded entry) — for the user this is the moment the login is lost, and a
debug-only line made it look like "I signed in once and Hermes keeps failing" (#113023). The
quarantine itself is unchanged; only the visibility is asserted here.
"""
from __future__ import annotations

import logging
import threading

import pytest

from agent import credential_pool as cp
from agent.credential_pool import CredentialPool, PooledCredential


def _pool(provider: str) -> CredentialPool:
    pool = CredentialPool.__new__(CredentialPool)
    pool._lock = threading.RLock()
    pool._entries = []
    pool._active_leases = {}
    pool._current_id = None
    pool._max_concurrent = 2
    pool._unmatched_rotation_streak = 0
    pool.provider = provider
    return pool


def _entry(provider: str) -> PooledCredential:
    return PooledCredential(id="e1", provider=provider, auth_type="oauth", access_token="dead-access",
                            refresh_token="dead-refresh", label="e1", source="device_code", priority=0)


@pytest.mark.parametrize(
    ("provider", "terminal_predicate", "sync_name", "clear_name", "expected_hint"),
    [
        ("openai-codex", "_is_terminal_codex_oauth_refresh_error", "_sync_entry_from_auth_store",
         "_clear_terminal_tokens_state", "hermes auth add openai-codex"),
        ("nous", "_is_terminal_nous_refresh_error", "_sync_nous_entry_from_auth_store",
         "_clear_terminal_nous_state", "hermes auth add nous"),
    ],
)
def test_terminal_refresh_quarantine_warns_with_reauth_hint(
    monkeypatch, caplog, provider, terminal_predicate, sync_name, clear_name, expected_hint,
):
    pool = _pool(provider)
    entry = _entry(provider)
    pool._entries = [entry]
    cleared: list = []
    monkeypatch.setattr(pool, sync_name, lambda e: e)  # no peer rotated in the meantime
    monkeypatch.setattr(pool, clear_name, lambda e, exc: cleared.append(e.id))
    monkeypatch.setattr(pool, "_quarantine_sources", lambda e, sources: None)
    monkeypatch.setattr(cp.auth_mod, terminal_predicate, lambda exc: True)

    with caplog.at_level(logging.INFO, logger=cp.logger.name):
        result = pool._recover_failed_refresh(entry, RuntimeError("invalid_grant"))

    assert result is None and cleared == ["e1"]
    warnings = [r for r in caplog.records if r.levelno == logging.WARNING and "terminally invalid" in r.getMessage()]
    assert len(warnings) == 1, [r.getMessage() for r in caplog.records]
    assert expected_hint in warnings[0].getMessage()
    assert "invalid_grant" in warnings[0].getMessage()


def test_nous_relogin_required_without_code_clears_state(monkeypatch, caplog):
    """A Nous refresh failure demanding relogin is terminal even when the provider
    supplied no dead code: benching it for an hour hides a lost login (#113718)."""
    from hermes_cli.auth_constants import AuthError

    pool = _pool("nous")
    entry = _entry("nous")
    pool._entries = [entry]
    cleared: list = []
    monkeypatch.setattr(pool, "_sync_nous_entry_from_auth_store", lambda e: e)
    monkeypatch.setattr(pool, "_clear_terminal_nous_state", lambda e, exc: cleared.append(e.id))
    monkeypatch.setattr(pool, "_quarantine_sources", lambda e, sources: None)

    exc = AuthError("session expired", provider="nous", code=None, relogin_required=True)
    with caplog.at_level(logging.INFO, logger=cp.logger.name):
        result = pool._recover_failed_refresh(entry, exc)

    assert result is None and cleared == ["e1"]
    warnings = [r for r in caplog.records if r.levelno == logging.WARNING and "terminally invalid" in r.getMessage()]
    assert len(warnings) == 1
    assert "hermes auth add nous" in warnings[0].getMessage()


def test_nous_transient_error_still_benched(monkeypatch, caplog):
    """Without relogin_required the failure stays transient: benched, not cleared."""
    pool = _pool("nous")
    entry = _entry("nous")
    pool._entries = [entry]
    cleared: list = []
    benched: list = []
    monkeypatch.setattr(pool, "_sync_nous_entry_from_auth_store", lambda e: e)
    monkeypatch.setattr(pool, "_clear_terminal_nous_state", lambda e, exc: cleared.append(e.id))
    monkeypatch.setattr(pool, "_mark_exhausted", lambda e, *a, **k: benched.append(e.id))

    with caplog.at_level(logging.INFO, logger=cp.logger.name):
        result = pool._recover_failed_refresh(entry, RuntimeError("boom"))

    assert result is None and cleared == [] and benched == ["e1"]
    assert not [r for r in caplog.records if r.levelno == logging.WARNING and "terminally invalid" in r.getMessage()]
