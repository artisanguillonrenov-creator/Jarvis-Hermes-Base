"""Opaque per-turn authorization state for trusted gateway metadata.

The bearer value is deliberately unavailable through public attributes, repr,
pickling, or JSON serialization.  Only the MCP call boundary can turn the
current holder into an HTTP header.
"""

from __future__ import annotations

import contextvars
import hmac
import math
import re
import time
from contextlib import contextmanager
from typing import Iterator
from typing import Any

FIZKO_PERSON_ACCESS_TOKEN_PARAM = "_fizko_person_access_token"
FIZKO_PERSON_ACCESS_TOKEN_EXPIRES_AT_PARAM = "_fizko_person_access_token_expires_at"

_BEARER_TOKEN = re.compile(r"[A-Za-z0-9._~+/=-]{1,16384}\Z")


class TurnAuthorization:
    __slots__ = ("__expires_at", "__token")

    def __init__(self, token: str | None, expires_at: float | None = None) -> None:
        self.__token = token
        self.__expires_at = expires_at

    @classmethod
    def from_raw(cls, raw: Any, *, expires_at: Any = None) -> "TurnAuthorization":
        if raw is None:
            if expires_at is not None:
                raise ValueError("person token expiry requires a bearer token")
            return cls(None)
        if not isinstance(raw, str) or not _BEARER_TOKEN.fullmatch(raw):
            raise ValueError("_fizko_person_access_token must be a non-empty bearer token")
        if expires_at is not None:
            if (
                isinstance(expires_at, bool)
                or not isinstance(expires_at, (int, float))
                or not math.isfinite(expires_at)
                or expires_at <= 0
            ):
                raise ValueError("_fizko_person_access_token_expires_at must be a Unix timestamp")
            expires_at = float(expires_at)
        return cls(raw, expires_at)

    def __repr__(self) -> str:
        return "<TurnAuthorization [REDACTED]>"

    @property
    def has_token(self) -> bool:
        return self.__token is not None

    @property
    def is_expired(self) -> bool:
        return self.__expires_at is not None and self.__expires_at <= time.time()

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
        return f"Bearer {self.__token}" if self.__token is not None and not self.is_expired else ""


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


def current_fizko_authorization_state() -> tuple[bool, str]:
    """Whether this is a personal turn and its still-valid Authorization header."""
    holder = _CURRENT_TURN_AUTHORIZATION.get()
    if holder is None or not holder.has_token:
        return False, ""
    return True, holder._fizko_authorization_header()
