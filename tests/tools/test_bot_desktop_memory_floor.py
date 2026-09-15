"""Bot Desktop refuses to start when the instance has no headroom for it.

Measured in the official image: gateway idle 304 MiB, +216 for Xvnc/Xfce, +553 once the bot opens one
browser page (peak 1115 MiB). The OOM killer picks a victim by score, so on a small instance the casualty
is the dashboard or the gateway rather than the desktop that caused the pressure.
"""
from __future__ import annotations

import pytest

from tools.bot_desktop import runtime


@pytest.fixture(autouse=True)
def _no_env_override(monkeypatch):
    monkeypatch.delenv("HERMES_BOT_DESKTOP_MIN_FREE_MEMORY_MB", raising=False)


def _write_cgroup(root, *, limit: str, current: int, inactive_file: int | None = None) -> None:
    (root / "memory.max").write_text(limit, encoding="utf-8")
    (root / "memory.current").write_text(str(current), encoding="utf-8")
    if inactive_file is not None:
        (root / "memory.stat").write_text(f"anon 123\ninactive_file {inactive_file}\nslab 7\n", encoding="utf-8")


MB = 1024 * 1024


def test_page_cache_does_not_count_against_the_limit(tmp_path, monkeypatch):
    """The regression this guards: a 4 GB instance idling at 643 MiB of mostly page cache must not read as
    643 MiB consumed. memory.current includes reclaimable cache, so charging it would make the check tighten
    the longer an instance stays up and refuse starts that would have been fine."""
    _write_cgroup(tmp_path, limit=str(4096 * MB), current=643 * MB, inactive_file=340 * MB)
    monkeypatch.setattr(runtime, "_CGROUP_ROOT", tmp_path)
    free = runtime._cgroup_free_mb()
    assert free == 4096 - (643 - 340), f"expected the working set to exclude page cache, got {free} MB free"


def test_start_refuses_when_headroom_is_below_the_floor(monkeypatch):
    monkeypatch.setattr(runtime, "is_supported_host", lambda: True)
    monkeypatch.setattr(runtime, "missing_binaries", lambda: [])
    monkeypatch.setattr(runtime, "free_memory_mb", lambda: 900)
    with pytest.raises(RuntimeError) as excinfo:
        runtime.start()
    message = str(excinfo.value)
    assert "900 MB" in message and "1536 MB" in message
    assert "min_free_memory_mb" in message, "the message must name the knob that relaxes it"


def test_start_allows_a_host_with_headroom(monkeypatch):
    monkeypatch.setattr(runtime, "free_memory_mb", lambda: 4096)
    runtime._refuse_below_memory_floor()  # does not raise


def test_unmeasurable_host_is_never_blocked(monkeypatch):
    """A host where neither the cgroup nor /proc/meminfo is readable keeps today's behaviour."""
    monkeypatch.setattr(runtime, "free_memory_mb", lambda: None)
    runtime._refuse_below_memory_floor()  # does not raise


def test_env_override_wins_and_zero_disables(monkeypatch):
    monkeypatch.setenv("HERMES_BOT_DESKTOP_MIN_FREE_MEMORY_MB", "512")
    monkeypatch.setattr(runtime, "free_memory_mb", lambda: 900)
    runtime._refuse_below_memory_floor()  # 900 clears a 512 floor

    monkeypatch.setenv("HERMES_BOT_DESKTOP_MIN_FREE_MEMORY_MB", "0")
    monkeypatch.setattr(runtime, "free_memory_mb", lambda: 10)
    runtime._refuse_below_memory_floor()  # disabled entirely


def test_unlimited_cgroup_falls_back_to_meminfo(tmp_path, monkeypatch):
    """`memory.max` = "max" means no container limit, so the host's own free memory is the real answer."""
    _write_cgroup(tmp_path, limit="max", current=100 * MB)
    monkeypatch.setattr(runtime, "_CGROUP_ROOT", tmp_path)
    assert runtime._cgroup_free_mb() is None


def test_unprivileged_host_cannot_install(monkeypatch):
    """The published image: unprivileged, no sudo. The packages can only arrive in the image."""
    monkeypatch.setattr(runtime, "package_manager", lambda: "apt")
    monkeypatch.setattr(runtime, "is_root", lambda: False)
    monkeypatch.setattr(runtime.shutil, "which", lambda name: None if name == "sudo" else "/usr/bin/" + name)
    assert runtime.installable() is False

    monkeypatch.setattr(runtime, "is_supported_host", lambda: True)
    monkeypatch.setattr(runtime, "missing_binaries", lambda: ["Xvnc"])
    with pytest.raises(RuntimeError, match="baked in"):
        runtime.start()
