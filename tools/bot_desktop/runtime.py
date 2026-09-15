"""Bot Desktop runtime: one headless Xfce desktop per Hermes profile, served over RFB on a private
Unix socket, viewed and driven from Hermes Desktop.

Layout under ``<HERMES_HOME>/bot-desktop/``: ``display`` (allocated X display number), ``rfb.sock``
(Xvnc RFB Unix socket, 0600), ``Xauthority``, ``env`` (DISPLAY/XAUTHORITY/DBUS_SESSION_BUS_ADDRESS
published by the launcher once Xfce's bus exists), ``launcher.pid``, ``launcher.log``, ``xdg/``
(per-profile XDG_CONFIG_HOME so two profiles never share xfconf). Everything is profile-scoped via
``get_hermes_home()`` so N profiles in one gateway get N desktops: one screen per bot on the shared
machine.

The launcher is ``launcher.sh`` next to this module; :func:`desktop_env` is what cua-driver and headed
Chromium spawns merge in so the agent acts on this profile's screen and nowhere else.
"""

from __future__ import annotations

import contextlib
import logging
import os
import shutil
import signal
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Optional

from hermes_constants import get_hermes_home

logger = logging.getLogger(__name__)

_LAUNCHER = Path(__file__).with_name("launcher.sh")

# Display numbers below 10 collide with real seats and default Xvfb recipes (:99 is popular too); scan a
# private band and record the choice so restarts reuse it.
_DISPLAY_MIN, _DISPLAY_MAX = 20, 89

# Binaries the launcher execs; the package hint is per distro family.
REQUIRED_BINARIES = ("Xvnc", "xfwm4", "xfce4-panel", "xfdesktop", "xfsettingsd", "dbus-run-session",
                     "xauth", "xdpyinfo", "setxkbmap", "xprop")

# Which package in each distro list ships each required binary. Fedora retired the xorg-x11-utils /
# xorg-x11-server-utils umbrellas (per-binary packages since F35) and dnf5 refuses the whole transaction on
# one unknown name, so every binary must map to a package that still resolves; the test suite checks that
# each mapped package is in PACKAGES for its manager.
BINARY_PACKAGES = {
    "apt": {"Xvnc": "tigervnc-standalone-server", "xfwm4": "xfwm4", "xfce4-panel": "xfce4-panel",
            "xfdesktop": "xfdesktop4", "xfsettingsd": "xfce4-settings", "dbus-run-session": "dbus-x11",
            "xauth": "xauth", "xdpyinfo": "x11-utils", "setxkbmap": "x11-xkb-utils", "xprop": "x11-utils"},
    # tigervnc-x11-server is the real package (tigervnc-server-minimal is only a Provides on it); dbus-run-session
    # is in dbus-daemon (dbus-x11 ships dbus-launch only).
    "dnf": {"Xvnc": "tigervnc-x11-server", "xfwm4": "xfwm4", "xfce4-panel": "xfce4-panel",
            "xfdesktop": "xfdesktop", "xfsettingsd": "xfce4-settings", "dbus-run-session": "dbus-daemon",
            "xauth": "xorg-x11-xauth", "xdpyinfo": "xdpyinfo", "setxkbmap": "setxkbmap", "xprop": "xprop"},
    "pacman": {"Xvnc": "tigervnc", "xfwm4": "xfwm4", "xfce4-panel": "xfce4-panel", "xfdesktop": "xfdesktop",
               "xfsettingsd": "xfce4-settings", "dbus-run-session": "dbus",
               "xauth": "xorg-xauth", "xdpyinfo": "xorg-xdpyinfo", "setxkbmap": "xorg-setxkbmap", "xprop": "xorg-xprop"},
}

PACKAGES = {
    "apt": ["tigervnc-standalone-server", "xfce4-panel", "xfwm4", "xfdesktop4", "xfce4-settings",
            "xfce4-terminal", "dbus-x11", "x11-xserver-utils", "x11-utils", "x11-xkb-utils", "xauth",
            "fonts-dejavu-core"],
    "dnf": ["tigervnc-x11-server", "xfce4-panel", "xfwm4", "xfdesktop", "xfce4-settings",
            "xfce4-terminal", "dbus-daemon", "xsetroot", "xset", "xdpyinfo", "xprop", "xorg-x11-xauth", "setxkbmap",
            "dejavu-sans-fonts"],
    "pacman": ["tigervnc", "xfce4-panel", "xfwm4", "xfdesktop", "xfce4-settings", "xfce4-terminal", "dbus",
               "xorg-xsetroot", "xorg-xset", "xorg-xdpyinfo", "xorg-xprop", "xorg-xauth", "xorg-setxkbmap",
               "ttf-dejavu"],
}


def state_dir() -> Path:
    return get_hermes_home() / "bot-desktop"


def is_supported_host() -> bool:
    return sys.platform.startswith("linux")


def missing_binaries() -> list[str]:
    return [b for b in REQUIRED_BINARIES if shutil.which(b) is None]


def package_manager() -> Optional[str]:
    for pm in ("apt-get", "dnf", "pacman"):
        if shutil.which(pm):
            return "apt" if pm == "apt-get" else pm
    return None


def install_command() -> Optional[str]:
    """The distro command that installs the Bot Desktop packages, as the human would type it on THIS host:
    prefixed with ``sudo`` unless Hermes already runs as root, so it is both what the pane shows and what
    :mod:`tools.bot_desktop.install` runs. ``None`` when no package manager is present.

    Not a promise that it can run here: see :func:`installable`. The published Docker image supervises
    every service under ``s6-setuidgid hermes`` (UID 10000 by default) and ships no ``sudo`` binary, so an
    install on a hosted instance is impossible no matter what this returns."""
    pm = package_manager()
    if pm is None:
        return None
    pkgs = " ".join(PACKAGES[pm])
    body = {
        "apt": f"apt-get install -y --no-install-recommends {pkgs}",
        "dnf": f"dnf install -y {pkgs}",
        "pacman": f"pacman -S --needed --noconfirm {pkgs}",
    }[pm]
    return body if is_root() else f"sudo {body}"


def is_root() -> bool:
    return hasattr(os, "geteuid") and os.geteuid() == 0


def installable() -> bool:
    """Whether :func:`install_command` could actually succeed on this host.

    False on an unprivileged process with no ``sudo`` to reach for, which is exactly the published Docker
    image: services drop to the ``hermes`` user and no ``sudo`` binary is installed. The packages can only
    arrive in the image there, so the pane must say that instead of offering a button that cannot work or
    printing a sudo line the user has no way to run.
    """
    if package_manager() is None:
        return False
    return is_root() or shutil.which("sudo") is not None


@dataclass
class DesktopStatus:
    profile: str
    supported: bool
    installed: bool
    missing: list[str]
    running: bool
    pid: Optional[int]
    display: Optional[str]
    socket: Optional[str]
    geometry: str
    install_command: Optional[str]
    browser: Optional[str]  # headed Chromium the dock's Browser icon and agent-browser share; None = no headed browser

    def as_dict(self) -> Dict[str, object]:
        return dict(self.__dict__)


def _read(path: Path) -> Optional[str]:
    try:
        return path.read_text(encoding="utf-8").strip() or None
    except OSError:
        return None


def _pid_alive(pid: int) -> bool:
    """A zombie is dead for our purposes: a SIGKILLed launcher stays a zombie in the gateway until the next
    Popen reaps it, and reporting it as running would hide its orphaned X server behind a live status."""
    import psutil
    try:
        return psutil.Process(pid).status() != psutil.STATUS_ZOMBIE
    except psutil.Error:
        return False


def _create_time(pid: int) -> Optional[float]:
    import psutil
    try:
        return psutil.Process(pid).create_time()
    except (psutil.Error, OverflowError, ValueError):
        return None


def _launcher_pid() -> Optional[int]:
    """The live launcher's pid, or None. ``launcher.pid`` holds ``"<pid> <create_time>"``: a recycled pid
    with a different start time is somebody else's process and must never be reported as ours nor
    killed by :func:`stop`. The pre-identity single-number format is treated as not running."""
    raw = _read(state_dir() / "launcher.pid")
    pid_s, _, born_s = (raw or "").partition(" ")
    if not pid_s.isdigit() or not born_s:
        return None
    try:
        pid, born = int(pid_s), float(born_s)
    except ValueError:
        return None
    actual = _create_time(pid)
    return pid if actual is not None and abs(actual - born) < 0.01 and _pid_alive(pid) else None


def _recorded_launcher_pid() -> Optional[int]:
    """The pid ``launcher.pid`` names, alive or not (the orphan sweep matches process groups against it)."""
    pid_s, _, born_s = (_read(state_dir() / "launcher.pid") or "").partition(" ")
    return int(pid_s) if pid_s.isdigit() and born_s else None


_X_LOCK_DIR = Path("/tmp")  # where X servers write .X<n>-lock (tests point it at a scratch dir)


def _x_lock_pid(num: int) -> Optional[int]:
    try:
        return int((_X_LOCK_DIR / f".X{num}-lock").read_text(encoding="utf-8").strip())
    except (OSError, ValueError):
        return None


def _display_in_use(num: int) -> bool:
    """A live X server owns ``:num``: its lock file names a running pid. A lock left by a crashed
    server (dead pid) does not count, so the number can be reclaimed."""
    pid = _x_lock_pid(num)
    return pid is not None and _pid_alive(pid)


def _reap_orphaned_server(sd: Path) -> bool:
    """Caller holds ``start.lock`` and has established that no live launcher exists. The launcher runs Xvnc in
    its own session, so a SIGKILLed launcher leaves the X server alive, holding the display and ``rfb.sock``;
    ``status()`` keys on the launcher and says stopped, and a naive restart allocates a second server next
    to it and overwrites the socket path both now claim. The X lock of the recorded display names that
    server: it is ours when it sits in the dead launcher's process group or its command line binds OUR
    socket. Kill it (group first), drop the state it left, and report whether anything was signalled."""
    import psutil

    recorded = _read(sd / "display")
    pid = _x_lock_pid(int(recorded)) if recorded and recorded.isdigit() else None
    if pid is None or not _pid_alive(pid):
        return False
    launcher = _recorded_launcher_pid()
    try:
        pgid = os.getpgid(pid)  # windows-footgun: ok — Linux-only runtime (is_supported_host gates start/stop)
        cmdline = psutil.Process(pid).cmdline()
    except (ProcessLookupError, psutil.Error):
        return False
    binds_our_socket = "Xvnc" in Path(cmdline[0] if cmdline else "").name and str(sd / "rfb.sock") in cmdline
    if pgid != launcher and not binds_our_socket:
        return False  # somebody else's server took the number after we died; never touch it
    logger.warning("Bot Desktop launcher %s is gone but its X server (pid %s) survived on :%s; reaping",
                   launcher, pid, recorded)
    _kill_group_then_wait(pgid if pgid == launcher else None, pid)
    (_X_LOCK_DIR / f".X{recorded}-lock").unlink(missing_ok=True)
    for name in ("launcher.pid", "env", "rfb.sock"):
        (sd / name).unlink(missing_ok=True)
    return True


def _kill_group_then_wait(pgid: Optional[int], pid: int, grace: float = 2.0) -> None:
    """SIGTERM the group (or the lone pid), SIGKILL whatever is still there after ``grace``."""
    def _signal(sig: int) -> None:
        with contextlib.suppress(ProcessLookupError, PermissionError):
            if pgid is not None:
                os.killpg(pgid, sig)  # windows-footgun: ok — Linux-only runtime (is_supported_host gates start/stop)
            else:
                os.kill(pid, sig)
    def _anything_left() -> bool:
        # The leader dying first is the common case (bash exits on TERM, Xvnc traps it); the group is
        # done only when killpg(0) finds nobody, else a TERM-ignoring descendant keeps the display.
        if pgid is None:
            return _pid_alive(pid)
        try:
            os.killpg(pgid, 0)  # windows-footgun: ok — Linux-only runtime (is_supported_host gates start/stop)
        except ProcessLookupError:
            return False
        except PermissionError:
            return True
        return True

    def _reap_if_ours() -> None:
        # The launcher was Popen'd by whichever gateway started it; a later gateway that stops it holds
        # no Popen, so the dead leader would sit as a zombie in our table (and count as "left").
        with contextlib.suppress(ChildProcessError, OSError):
            os.waitpid(pid, os.WNOHANG)  # windows-footgun: ok — Linux-only runtime (is_supported_host gates start/stop)

    _signal(signal.SIGTERM)
    deadline = time.monotonic() + grace
    while time.monotonic() < deadline:
        _reap_if_ours()
        if not _anything_left():
            return
        time.sleep(0.05)
    _signal(signal.SIGKILL)  # windows-footgun: ok — Linux-only runtime (is_supported_host gates start/stop)
    time.sleep(0.1)
    _reap_if_ours()


# Host-wide (every profile allocates from one band), so it lives outside any profile home — but not in
# world-writable /tmp, where a predictable name lets another local user pre-create or squat the file.
_ALLOC_LOCK = Path(os.environ.get("XDG_RUNTIME_DIR") or Path.home() / ".cache") / "hermes-bot-desktop-alloc.lock"


@contextlib.contextmanager
def _flocked(path: Path):
    import fcntl  # windows-footgun: ok — Linux-only runtime (is_supported_host gates start)
    with open(path, "a+", encoding="utf-8") as fh:  # windows-footgun: ok — Linux-only runtime
        fcntl.flock(fh.fileno(), fcntl.LOCK_EX)
        try:
            yield fh
        finally:
            fcntl.flock(fh.fileno(), fcntl.LOCK_UN)


def _pick_display() -> int:
    """Caller holds ``_ALLOC_LOCK``. The recorded number is only reused when no OTHER server holds it now:
    after profile A stops, B may have taken A's number, and A's launcher must never unlink B's socket."""
    recorded = _read(state_dir() / "display")
    if recorded and recorded.isdigit() and not _display_in_use(int(recorded)):
        return int(recorded)
    for num in range(_DISPLAY_MIN, _DISPLAY_MAX + 1):
        if not _display_in_use(num):
            return num
    raise RuntimeError("no free X display number in the Bot Desktop band")


def _allocate_display() -> int:
    _ALLOC_LOCK.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with _flocked(_ALLOC_LOCK):
        return _pick_display()


def desktop_env(base_env: Optional[Dict[str, str]] = None) -> Dict[str, str]:
    """``base_env`` (default ``os.environ``) with this profile's DISPLAY/XAUTHORITY/DBUS_SESSION_BUS_ADDRESS
    merged in when its desktop is running. Unchanged otherwise, so hosts with a real seat keep it.
    Pure: never starts anything (it is called from env builders, status probes and tests)."""
    env = dict(os.environ if base_env is None else base_env)
    published = published_env()
    if published:
        env.update(published)
        env.pop("WAYLAND_DISPLAY", None)  # X11 desktop; a leaked Wayland socket flips GTK/Chromium backends
        from tools.bot_desktop.browser import env_for_agent
        env_for_agent(env)  # same binary + user-data-dir as the dock's Browser icon
    return env


def ensure_started_for_tool() -> None:
    """Tool-boundary hook (``computer_use`` dispatch): with ``bot_desktop.auto_start`` (opt-in, default off) a Linux
    host that has NO display and the packages installed gets its screen started on first use, so a headless
    gateway works the first time instead of answering "no DISPLAY is set". Failure is not an error here;
    the tool's own "no display" diagnosis is the right message then."""
    if published_env() or not _should_auto_start(os.environ):
        return
    try:
        start()
    except Exception as exc:
        logger.info("Bot Desktop auto-start skipped: %s", exc)


def _should_auto_start(env: Dict[str, str]) -> bool:
    if not is_supported_host() or env.get("DISPLAY") or env.get("WAYLAND_DISPLAY"):
        return False
    if missing_binaries():
        return False
    from hermes_cli.config import load_config_readonly
    cfg = load_config_readonly().get("bot_desktop") or {}
    return bool(cfg.get("auto_start", False))


def published_env() -> Dict[str, str]:
    """Variables the launcher wrote once Xfce's private bus existed; empty when the desktop is down."""
    if _launcher_pid() is None:
        return {}
    raw = _read(state_dir() / "env")
    if not raw:
        return {}
    out: Dict[str, str] = {}
    for line in raw.splitlines():
        key, sep, value = line.partition("=")
        if sep:
            out[key.strip()] = value.strip()
    return out


def rfb_socket_path() -> Optional[Path]:
    sock = state_dir() / "rfb.sock"
    return sock if _launcher_pid() is not None and sock.exists() else None


def geometry() -> str:
    from hermes_cli.config import load_config_readonly
    cfg = load_config_readonly().get("bot_desktop") or {}
    return str(cfg.get("geometry") or "1440x900")


def status(profile: Optional[str] = None) -> DesktopStatus:
    from tools.bot_desktop import browser as _bd_browser
    missing: list[str] = missing_binaries() if is_supported_host() else list(REQUIRED_BINARIES)
    pid = _launcher_pid()
    env = published_env()
    return DesktopStatus(
        profile=profile or _profile_name(),
        supported=is_supported_host(),
        installed=not missing,
        missing=missing,
        running=pid is not None and bool(env.get("DISPLAY")),
        pid=pid,
        display=env.get("DISPLAY"),
        socket=str(rfb_socket_path()) if rfb_socket_path() else None,
        geometry=geometry(),
        install_command=install_command() if missing else None,
        browser=_bd_browser.executable() if is_supported_host() else None,
    )


def _profile_name() -> str:
    try:
        from hermes_cli.profiles import get_active_profile_name
        return get_active_profile_name() or "default"
    except Exception:
        return "default"


# Measured in the official image (cgroup memory.current): gateway idle 304 MiB; Xvnc + Xfce with nothing
# open 520 MiB (+216); one Chromium page 1073 MiB (+553, peak 1115). The browser dominates and that is the
# point of the feature, so the desktop is not something to squeeze under a budget. What we can do is refuse
# to start when there is not enough headroom, because the kernel OOM killer picks a victim by score, not by
# who caused the pressure: on a small instance it takes out the dashboard or the gateway and the desktop
# survives, which surfaces as an unrelated outage nobody traces back to here.
_MIN_FREE_MEMORY_MB = 1536   # refuse below this much headroom
_WARN_FREE_MEMORY_MB = 2048  # start, but say it is tight
_CGROUP_ROOT = Path("/sys/fs/cgroup")  # tests point it at a scratch dir
_MEMINFO = Path("/proc/meminfo")       # same


def _read_int(path: Path) -> Optional[int]:
    try:
        return int(path.read_text(encoding="utf-8").strip())
    except Exception:
        return None


def _cgroup_free_mb() -> Optional[int]:
    """Headroom against the cgroup's own limit, or None when unlimited/unreadable.

    The limit is what the OOM killer enforces, and ``/proc/meminfo`` does not report it: on a container host
    meminfo describes the NODE, so a headroom check there is meaningless (it happens to be right on a Fly
    machine only because those are microVMs). Cgroup v2 layout, which is what current Docker/containerd give.

    ``memory.current`` counts reclaimable page cache, which is why a container reads several hundred MB above
    idle right after a desktop stops even though every process is gone. Charging that against the limit would
    make the check tighten the longer an instance stays up and refuse starts that would have been fine, so we
    subtract ``inactive_file`` (the working-set convention kubelet uses).
    """
    root = _CGROUP_ROOT
    try:
        limit_raw = (root / "memory.max").read_text(encoding="utf-8").strip()
    except Exception:
        return None
    if limit_raw == "max":  # no limit set: the host's own free memory is the real answer
        return None
    try:
        limit = int(limit_raw)
    except ValueError:
        return None
    current = _read_int(root / "memory.current")
    if current is None:
        return None
    inactive_file = 0
    try:
        for line in (root / "memory.stat").read_text(encoding="utf-8").splitlines():
            if line.startswith("inactive_file "):
                inactive_file = int(line.split()[1])
                break
    except Exception:
        pass
    working_set = max(current - inactive_file, 0)
    return max(limit - working_set, 0) // (1024 * 1024)


def _meminfo_free_mb() -> Optional[int]:
    """MemAvailable, the fallback for an unlimited cgroup or cgroup v1. None when unreadable."""
    try:
        for line in _MEMINFO.read_text(encoding="utf-8").splitlines():
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) // 1024
    except Exception:
        return None
    return None


def free_memory_mb() -> Optional[int]:
    """Memory a desktop could actually use here. None when nothing on this host can tell us."""
    free = _cgroup_free_mb()
    return free if free is not None else _meminfo_free_mb()


def _min_free_memory_mb() -> int:
    """``bot_desktop.min_free_memory_mb``, overridable by env so a hosted deployment can set it per instance
    without templating a config file. 0 or negative disables the check."""
    override = os.environ.get("HERMES_BOT_DESKTOP_MIN_FREE_MEMORY_MB", "").strip()
    if not override:
        from hermes_cli.config import load_config_readonly
        cfg = load_config_readonly().get("bot_desktop") or {}
        configured = cfg.get("min_free_memory_mb")
        if configured is None:
            return _MIN_FREE_MEMORY_MB
        override = str(configured).strip()
    try:
        return int(override)
    except (TypeError, ValueError):
        logger.warning("Ignoring non-numeric bot_desktop.min_free_memory_mb=%r", override)
        return _MIN_FREE_MEMORY_MB


def _refuse_below_memory_floor() -> None:
    """Raise when this host has too little headroom for a desktop plus the browser that is the point of it."""
    floor = _min_free_memory_mb()
    if floor <= 0:
        return
    free = free_memory_mb()
    if free is None:  # nothing readable: do not stand in the way of a host we cannot measure
        return
    if free < floor:
        raise RuntimeError(
            f"Bot Desktop needs about {floor} MB of free memory to start and this host has {free} MB. "
            "A desktop plus the bot's browser runs past 1 GB, so starting here would likely get another "
            "process OOM-killed instead. Give the instance more memory, or lower "
            "bot_desktop.min_free_memory_mb if you accept the risk.")
    if free < _WARN_FREE_MEMORY_MB:
        logger.warning(
            "Bot Desktop starting with %d MB free; a browser with a few pages open can use most of that.", free)


def start(*, wait_seconds: float = 15.0) -> DesktopStatus:
    """Start this profile's desktop (idempotent). Blocks until the launcher publishes its env file or
    ``wait_seconds`` pass; raises ``RuntimeError`` naming the blocker.

    Two locks: the per-profile ``start.lock``, held from the running-check to the launcher's publish so two
    start() calls for one profile spawn one launcher (the loser sees it running), and the host-wide
    display-allocation lock, held only until this Xvnc has written ``/tmp/.X<n>-lock`` (a second profile
    picking the same number before that would fail and its stale-lock cleanup could remove our socket).
    Holding it for the whole Xfce bring-up serialized every profile's start behind one desktop launch."""
    if not is_supported_host():
        raise RuntimeError("Bot Desktop runs on Linux gateway hosts only")
    missing = missing_binaries()
    if missing:
        if not installable():
            raise RuntimeError(
                f"Bot Desktop needs {', '.join(missing)} on the gateway host, and this host cannot install "
                "them: the process is unprivileged and there is no sudo. On the published Docker image the "
                "packages have to be baked in, so this needs a newer image rather than an install.")
        hint = install_command() or "install TigerVNC (Xvnc) and the Xfce core components"
        raise RuntimeError(f"Bot Desktop needs {', '.join(missing)} on the gateway host. Install: {hint}")
    _refuse_below_memory_floor()
    sd = state_dir()
    sd.mkdir(parents=True, exist_ok=True)
    os.chmod(sd, 0o700)
    with _flocked(sd / "start.lock"):
        if _launcher_pid() is not None and published_env().get("DISPLAY"):
            return status()
        if _launcher_pid() is None:
            _reap_orphaned_server(sd)
        _ALLOC_LOCK.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        return _spawn_and_wait(sd, wait_seconds)


def _spawn_and_wait(sd: Path, wait_seconds: float) -> DesktopStatus:
    """Caller holds ``start.lock``. Takes ``_ALLOC_LOCK`` itself, from picking the number to Xvnc's claim."""
    with contextlib.ExitStack() as alloc:
        alloc.enter_context(_flocked(_ALLOC_LOCK))
        num = _pick_display()
        (sd / "display").write_text(str(num), encoding="utf-8")
        env_file = sd / "env"
        env_file.unlink(missing_ok=True)

        child_env = {k: v for k, v in os.environ.items() if k not in {
            "DISPLAY", "XAUTHORITY", "WAYLAND_DISPLAY", "DBUS_SESSION_BUS_ADDRESS", "SESSION_MANAGER"}}
        child_env.update({
            "HERMES_BD_PROFILE": _profile_name(),
            "HERMES_BD_DISPLAY_NUM": str(num),
            "HERMES_BD_SOCKET": str(sd / "rfb.sock"),
            "HERMES_BD_XAUTH": str(sd / "Xauthority"),
            "HERMES_BD_ENV_FILE": str(env_file),
            "HERMES_BD_CONFIG_HOME": str(sd / "xdg"),
            "HERMES_BD_GEOMETRY": geometry(),
        })
        from tools.bot_desktop.browser import dock_exec_line, dock_launch
        if (browser := dock_launch()) is not None:
            # The bare executable (the launcher checks it exists) and the ready-made, spec-quoted Exec= line.
            child_env["HERMES_BD_BROWSER_EXEC"] = browser[0]
            child_env["HERMES_BD_BROWSER_EXEC_LINE"] = dock_exec_line(*browser)
        # Truncated per start: the log is a diagnostic for THIS launch, and nothing rotates it otherwise.
        log = open(sd / "launcher.log", "wb")  # noqa: SIM115 — handed to the child, closed by it
        proc = subprocess.Popen(  # windows-footgun: ok — Linux-only runtime (is_supported_host)
            ["bash", str(_LAUNCHER)], env=child_env, stdin=subprocess.DEVNULL, stdout=log, stderr=log,
            start_new_session=True, close_fds=True)
        log.close()
        born = _create_time(proc.pid)
        (sd / "launcher.pid").write_text(f"{proc.pid} {born if born is not None else 0}", encoding="utf-8")

        deadline = time.monotonic() + wait_seconds
        while time.monotonic() < deadline:
            if _x_lock_pid(num) is not None:
                alloc.close()  # the number is Xvnc's now; other profiles may allocate (idempotent)
            if proc.poll() is not None:
                tail = (sd / "launcher.log").read_bytes()[-2000:].decode("utf-8", "replace")
                raise RuntimeError(f"Bot Desktop launcher exited with {proc.returncode}:\n{tail}")
            if env_file.exists() and (sd / "rfb.sock").exists():
                logger.info("Bot Desktop for profile %s up on :%s", _profile_name(), num)
                return status()
            time.sleep(0.1)
        # Giving up must take the launch down: left alone, the launcher publishes DISPLAY and rfb.sock a moment
        # later and a screen whose start() reported failure stays up as "running". The launcher is its own
        # session leader, so its group is exactly this launch (Xvnc, dbus, Xfce) and nothing else.
        _kill_group_then_wait(proc.pid, proc.pid)
        proc.wait()
        for name in ("launcher.pid", "env", "rfb.sock"):
            (sd / name).unlink(missing_ok=True)
        raise RuntimeError(f"Bot Desktop did not publish its display within {wait_seconds:.0f}s (see {sd / 'launcher.log'})")


def stop() -> bool:
    """Stop this profile's desktop; True when a running launcher (or the X server a dead one left behind)
    was signalled."""
    if not is_supported_host():
        return False
    sd = state_dir()
    sd.mkdir(parents=True, exist_ok=True)
    with _flocked(sd / "start.lock"):
        return _stop_locked(sd)


def _stop_locked(sd: Path) -> bool:
    pid = _launcher_pid()
    if pid is None:
        reaped = _reap_orphaned_server(sd)
        (sd / "env").unlink(missing_ok=True)
        return reaped
    # The launcher runs in its own session; killing the group takes Xvnc, dbus and Xfce with it.
    _kill_group_then_wait(pid, pid, grace=5.0)
    (sd / "launcher.pid").unlink(missing_ok=True)
    (sd / "env").unlink(missing_ok=True)
    return True
