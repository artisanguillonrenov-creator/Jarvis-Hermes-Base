"""Opaque per-turn authorization state for trusted gateway metadata.

The bearer value is deliberately unavailable through public attributes, repr,
pickling, or JSON serialization.  Only the MCP call boundary can turn the
current holder into an HTTP header.
"""

from __future__ import annotations

import contextvars
import hmac
import re
from contextlib import contextmanager
from typing import Iterator
from typing import Any

FIZKO_PERSON_ACCESS_TOKEN_PARAM = "_fizko_person_access_token"

_BEARER_TOKEN = re.compile(r"[A-Za-z0-9._~+/=-]{1,16384}\Z")


class TurnAuthorization:
    __slots__ = ("__token",)

    def __init__(self, token: str | None) -> None:
        self.__token = token

    @classmethod
    def from_raw(cls, raw: Any) -> "TurnAuthorization":
        if raw is None:
            return cls(None)
        if not isinstance(raw, str) or not _BEARER_TOKEN.fullmatch(raw):
            raise ValueError("_fizko_person_access_token must be a non-empty bearer token")
        return cls(raw)

    def __repr__(self) -> str:
        return "<TurnAuthorization [REDACTED]>"

    @property
    def has_token(self) -> bool:
        return self.__token is not None

    __str__ = __repr__

    def __reduce__(self):
        raise TypeError("TurnAuthorization cannot be serialized")

    def same_credential(self, other: object) -> bool:
        if not isinstance(other, TurnAuthorization):
            return False
        if self.__token is None or other.__token is None:
            return self.__token is other.__token
        return hmac.compare_digest(self.__token, other.__token)

    def _fizko_authorization_header(self) -> str:
        return f"Bearer {self.__token}" if self.__token is not None else ""


_CURRENT_TURN_AUTHORIZATION: contextvars.ContextVar[TurnAuthorization | None] = contextvars.ContextVar(
    "hermes_current_turn_authorization", default=None
)


def set_current_turn_authorization(holder: TurnAuthorization):
    return _CURRENT_TURN_AUTHORIZATION.set(holder)


def reset_current_turn_authorization(token) -> None:
    _CURRENT_TURN_AUTHORIZATION.reset(token)


@contextmanager
def without_turn_authorization() -> Iterator[None]:
    """Prevent parent-turn authority from entering delegated or detached work."""
    token = _CURRENT_TURN_AUTHORIZATION.set(None)
    try:
        yield
    finally:
        _CURRENT_TURN_AUTHORIZATION.reset(token)


def current_fizko_authorization_header() -> str:
    """Authorization override for an opted-in Fizko call; empty means fail closed."""
    holder = _CURRENT_TURN_AUTHORIZATION.get()
    return holder._fizko_authorization_header() if holder is not None else ""
