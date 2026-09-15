"""Contract tests for the Bot Screen desktop packages baked into the Docker image.

Hosted deployments cannot install them at runtime: supervised services drop to the
unprivileged ``hermes`` user, the image ships no ``sudo``, and /opt/hermes is sealed.
The image layer is the only delivery path, so these tests guard it against drift.
"""
from __future__ import annotations

import re
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
DOCKERFILE = REPO_ROOT / "Dockerfile"


def _desktop_packages_in_dockerfile() -> set[str]:
    """Package names from the Bot Screen apt layer, as the Dockerfile lists them."""
    text = DOCKERFILE.read_text()
    marker = "# ---------- Bot Screen desktop packages ----------"
    assert marker in text, "Bot Screen apt layer is gone from the Dockerfile"
    layer = text.split(marker, 1)[1].split("\nRUN mkdir -p /tmp/.X11-unix", 1)[0]
    body = layer.split("--no-install-recommends", 1)[1].split("rm -rf", 1)[0]
    return {tok for tok in re.split(r"[\s\\&]+", body) if tok and not tok.startswith("-")}


def test_desktop_layer_installs_an_x_server_and_a_window_manager() -> None:
    packages = _desktop_packages_in_dockerfile()
    # Xvnc renders to memory and needs no privileged device access, which is what
    # makes the screen runnable as UID 10000 once the binaries are present.
    assert "tigervnc-standalone-server" in packages
    assert "xfwm4" in packages
    # dbus-x11 ships dbus-run-session, which the launcher wraps the session in.
    assert "dbus-x11" in packages
    # The xfce4 metapackage would drag in a screensaver, a power manager and a
    # polkit agent, none of which mean anything on a headless desktop.
    assert "xfce4" not in packages


def test_image_ships_a_headed_chromium_not_only_the_headless_shell() -> None:
    text = DOCKERFILE.read_text()
    # chrome-headless-shell can drive pages but cannot open a window, so a human
    # who takes over the screen would have no browser to log in with.
    assert "npx playwright install --with-deps chromium --only-shell" in text
    assert re.search(r"npx playwright install --with-deps chromium\s*&&", text), \
        "the full headed chromium build is no longer installed"


def test_container_gets_an_xdg_runtime_dir_outside_the_data_volume() -> None:
    text = DOCKERFILE.read_text()
    match = re.search(r"^ENV XDG_RUNTIME_DIR=(\S+)$", text, re.MULTILINE)
    assert match, "XDG_RUNTIME_DIR is unset; Xfce and the display-alloc lock fall back to $HOME/.cache"
    # $HERMES_HOME is commonly bind-mounted and sometimes shared with a host-side
    # install: two instances would then contend for one display-allocation lock.
    assert not match.group(1).startswith("/opt/data")


def test_dockerfile_package_list_matches_the_runtime_install_command() -> None:
    """The baked list must stay identical to what a self-hosted operator would install.

    Skipped until the Bot Screen runtime lands (NousResearch/hermes-agent#108914);
    it starts enforcing the moment tools/bot_desktop/runtime.py exists.
    """
    runtime = pytest.importorskip(
        "tools.bot_desktop.runtime",
        reason="Bot Screen runtime not merged yet (#108914)",
    )
    assert _desktop_packages_in_dockerfile() == set(runtime.PACKAGES["apt"])


def test_every_required_binary_is_covered_by_a_baked_package() -> None:
    runtime = pytest.importorskip(
        "tools.bot_desktop.runtime",
        reason="Bot Screen runtime not merged yet (#108914)",
    )
    baked = _desktop_packages_in_dockerfile()
    missing = {
        binary: pkg
        for binary, pkg in runtime.BINARY_PACKAGES["apt"].items()
        if pkg not in baked
    }
    assert not missing, f"binaries the image would still be missing at runtime: {missing}"
