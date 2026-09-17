"""A historical receipt cannot outweigh complete, identity-matched live evidence."""

import json

import pytest

from hermes_cli import update_cmd, update_cmd_fleet, update_receipt
from hermes_constants import get_hermes_home


@pytest.mark.parametrize("profiles", [["alpha"], ["alpha", "beta"]])
def test_current_successors_settle_historical_obligations(monkeypatch, profiles):
    home = get_hermes_home()
    directory = home / "logs" / "update_receipts"
    directory.mkdir(parents=True)
    receipt = {
        "outcome": "failed",
        "plan": {
            "runtimes": [
                {"kind": "gateway", "profile": p, "pid": 1, "code_sha": "old"}
                for p in profiles
            ]
        },
    }
    path = directory / "latest.json"
    path.write_text(json.dumps(receipt))
    monkeypatch.setattr(update_cmd, "_current_checkout_sha", lambda: "new")
    monkeypatch.setattr(
        update_receipt,
        "collect_fleet_versions",
        lambda: [
            {"profile": p, "pid": 2, "state": "current", "code_sha": "new"}
            for p in profiles
        ],
    )
    assert not update_cmd_fleet._pending_fleet_restart_needed()
    assert (
        json.loads(path.read_text()) == receipt
    )  # Historical failure remains truthful.


@pytest.mark.parametrize(
    ("live_state", "settled"),
    [("current", True), ("stale", False)],
)
def test_non_gateway_receipt_runtime_does_not_block_gateway_reconciliation(
    monkeypatch, live_state, settled
):
    """A serve receipt is not gateway evidence, but stale live rows still are."""
    directory = get_hermes_home() / "logs" / "update_receipts"
    directory.mkdir(parents=True)
    (directory / "latest.json").write_text(json.dumps({
        "outcome": "failed",
        "plan": {"runtimes": [
            {"kind": "gateway", "profile": "default", "code_sha": "old"},
            {"kind": "serve", "profile": "default", "code_sha": None},
        ]},
    }))
    monkeypatch.setattr(update_cmd, "_current_checkout_sha", lambda: "new")
    monkeypatch.setattr(update_receipt, "collect_fleet_versions", lambda: [{
        "profile": "default", "state": live_state, "code_sha": "new",
    }])

    assert update_cmd_fleet._pending_fleet_restart_needed() is not settled


@pytest.mark.parametrize(
    "bad",
    [
        "missing",
        "unknown",
        "down",
        "stale",
        "wrong-sha",
        "unknown-kind",
        "unknown-profile",
        "marker",
    ],
)
def test_every_owed_identity_requires_current_evidence(monkeypatch, bad):
    home = get_hermes_home()
    directory = home / "logs" / "update_receipts"
    directory.mkdir(parents=True)
    if bad == "marker":
        # Obligation for an SHA the fleet does not serve: no verified discharge.
        (home / "fleet_restart_pending").write_text("expected_sha=future\n")
    owed = {"kind": "gateway", "profile": "beta", "code_sha": "old"}
    if bad == "unknown-kind":
        owed["kind"] = "unknown-runtime"
    if bad == "unknown-profile":
        owed["profile"] = "unknown"
    (directory / "latest.json").write_text(
        json.dumps({
            "outcome": "failed",
            "plan": {
                "runtimes": [
                    {"kind": "gateway", "profile": "alpha", "code_sha": "old"},
                    owed,
                ]
            },
        })
    )
    rows = [{"profile": "alpha", "state": "current", "code_sha": "new"}]
    if bad != "missing":
        rows.append({
            "profile": "beta",
            "state": bad if bad in {"unknown", "down", "stale"} else "current",
            "code_sha": "old" if bad == "wrong-sha" else "new",
        })
    monkeypatch.setattr(update_cmd, "_current_checkout_sha", lambda: "new")
    monkeypatch.setattr(update_receipt, "collect_fleet_versions", lambda: rows)
    assert update_cmd_fleet._pending_fleet_restart_needed()
