"""Regression coverage for configured-provider routing compatibility views."""

from hermes_cli.config import get_compatible_custom_providers
from hermes_cli.model_switch import _configured_provider_matches


def test_configured_provider_matches_collapses_generated_compatibility_view():
    """A v12 provider and its legacy projection are one routing endpoint."""
    providers = {
        "relay": {
            "name": "relay",
            "api": "https://relay.example.test/v1",
            "key_env": "RELAY_API_KEY",
            "transport": "anthropic_messages",
            "default_model": "claude-opus-4-7",
        }
    }

    matches = _configured_provider_matches(
        "claude-opus-4-7",
        providers,
        get_compatible_custom_providers({"providers": providers}),
    )

    assert matches == {"relay": "claude-opus-4-7"}


def test_configured_provider_matches_keeps_distinct_endpoints_ambiguous():
    """A different endpoint with the same model remains a separate candidate."""
    providers = {
        "relay": {
            "name": "relay",
            "api": "https://relay.example.test/v1",
            "key_env": "RELAY_API_KEY",
            "transport": "anthropic_messages",
            "default_model": "claude-opus-4-7",
        }
    }
    distinct_legacy_provider = {
        "name": "backup-relay",
        "base_url": "https://backup.example.test/v1",
        "key_env": "BACKUP_RELAY_API_KEY",
        "api_mode": "anthropic_messages",
        "model": "claude-opus-4-7",
    }

    matches = _configured_provider_matches(
        "claude-opus-4-7", providers, [distinct_legacy_provider]
    )

    assert matches == {
        "relay": "claude-opus-4-7",
        "custom:backup-relay": "claude-opus-4-7",
    }


def test_configured_provider_matches_keeps_distinct_provider_keys_ambiguous():
    """Separate provider keys remain distinct even with matching display metadata."""
    provider = {
        "name": "relay",
        "api": "https://relay.example.test/v1",
        "key_env": "RELAY_API_KEY",
        "transport": "anthropic_messages",
        "default_model": "claude-opus-4-7",
    }

    matches = _configured_provider_matches(
        "claude-opus-4-7",
        {"relay-primary": provider, "relay-backup": dict(provider)},
        [],
    )

    assert matches == {
        "relay-primary": "claude-opus-4-7",
        "relay-backup": "claude-opus-4-7",
    }
