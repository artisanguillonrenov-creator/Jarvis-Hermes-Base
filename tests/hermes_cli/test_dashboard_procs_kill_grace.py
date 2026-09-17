"""The dashboard SIGTERM→SIGKILL grace must outlast the lifespan teardown (#111912).

``hermes update`` / ``hermes dashboard --stop`` fall back to ``_kill_pids_posix`` for a
manually-started backend. Its lifespan teardown blocks on ``stop_hosted_room_service(timeout=5.0)``
before ``PTY_REGISTRY.close_all()`` runs; a SIGKILL inside that window orphans the ui-tui /
tui_gateway.entry children, which keep the deleted ``state.db-wal`` inode open until the next
start aborts with ``DeletedWalGenerationError``. Real child processes, real signals.
"""

from __future__ import annotations

import os
import signal
import subprocess
import sys
import textwrap
import time

import pytest

from hermes_cli import dashboard_procs

pytestmark = [
    pytest.mark.skipif(sys.platform == "win32", reason="POSIX signal semantics only"),
    pytest.mark.live_system_guard_bypass,
]

# Mirrors the lifespan: sleep for the teardown budget on SIGTERM, then leave a marker and exit 0.
_GRACEFUL_CHILD = textwrap.dedent(
    """
    import pathlib, signal, sys, time
    marker, ready, secs = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), float(sys.argv[3])
    def _on_term(_signum, _frame):
        time.sleep(secs)
        marker.write_text("teardown-complete")
        sys.exit(0)
    signal.signal(signal.SIGTERM, _on_term)
    ready.write_text("ready")
    time.sleep(300)
    """
)

_IGNORING_CHILD = textwrap.dedent(
    """
    import pathlib, signal, sys, time
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    pathlib.Path(sys.argv[1]).write_text("ready")
    time.sleep(300)
    """
)

_WEDGED_DESCENDANT = textwrap.dedent(
    """
    import os, pathlib, signal, sys, time
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    pathlib.Path(sys.argv[1]).write_text(str(os.getpid()))
    time.sleep(300)
    """
)

_WEDGED_DESCENDANT_PARENT = textwrap.dedent(
    f"""
    import pathlib, signal, subprocess, sys, time
    pid_path, ready = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
    child = subprocess.Popen([sys.executable, "-c", {_WEDGED_DESCENDANT!r}, str(pid_path)])
    while not pid_path.exists():
        time.sleep(0.01)
    ready.write_text("ready")
    time.sleep(300)
    """
)


def _spawn_ready(script: str, ready_path, *args: str) -> subprocess.Popen:
    child = subprocess.Popen(
        [sys.executable, "-c", script, *args],
        stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    deadline = time.monotonic() + 10.0
    while not ready_path.exists():  # the SIGTERM handler must be installed before we signal
        if child.poll() is not None:
            raise AssertionError(f"child exited before signaling ready: {child.returncode}")
        if time.monotonic() > deadline:
            raise AssertionError("child never signaled ready")
        time.sleep(0.02)
    return child


def _kill_and_reap(child: subprocess.Popen):
    killed: list[int] = []
    failed: list[tuple[int, str]] = []
    dashboard_procs._kill_pids_posix([child.pid], killed, failed)
    try:
        child.wait(timeout=10)
    finally:
        if child.poll() is None:
            child.kill()
            child.wait(timeout=10)
    return killed, failed


def _pid_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    status = subprocess.run(
        ["ps", "-o", "stat=", "-p", str(pid)], capture_output=True, text=True, check=False,
    ).stdout.strip()
    return bool(status) and not status.startswith("Z")


def test_teardown_as_long_as_lifespan_budget_exits_gracefully(tmp_path):
    """A teardown spanning the 5s hosted-room stop + 1s join must not be SIGKILLed."""
    marker, ready = tmp_path / "marker", tmp_path / "ready"
    child = _spawn_ready(_GRACEFUL_CHILD, ready, str(marker), str(ready), "6.2")

    killed, failed = _kill_and_reap(child)

    assert failed == []
    assert child.returncode == 0, f"SIGKILLed mid-teardown (rc={child.returncode})"
    assert marker.read_text() == "teardown-complete"
    assert killed == [child.pid]


def test_sigterm_ignoring_process_is_still_sigkilled(tmp_path, monkeypatch):
    """The grace is a ceiling, not a wait: a process that ignores SIGTERM is force-killed."""
    monkeypatch.setattr(dashboard_procs, "_POSIX_TERM_GRACE_SECONDS", 0.6)
    ready = tmp_path / "ready"
    child = _spawn_ready(_IGNORING_CHILD, ready, str(ready))

    killed, failed = _kill_and_reap(child)

    assert failed == []
    assert child.returncode == -signal.SIGKILL
    assert killed == [child.pid]


def test_wedged_descendant_is_killed_after_its_backend_exits(tmp_path, monkeypatch):
    """A descendant must stay targeted after SIGTERM reparented it away from the backend."""
    monkeypatch.setattr(dashboard_procs, "_POSIX_TERM_GRACE_SECONDS", 0.6)
    pid_path, ready = tmp_path / "descendant.pid", tmp_path / "ready"
    backend = _spawn_ready(_WEDGED_DESCENDANT_PARENT, ready, str(pid_path), str(ready))
    descendant_pid = int(pid_path.read_text())

    try:
        killed, failed = _kill_and_reap(backend)

        assert failed == []
        assert killed == [backend.pid]
        assert not _pid_alive(descendant_pid), "wedged descendant survived backend shutdown"
    finally:
        if _pid_alive(descendant_pid):
            os.kill(descendant_pid, signal.SIGKILL)
