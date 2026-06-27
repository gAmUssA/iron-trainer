"""Per-request current-user resolution for multi-tenant data isolation.

The current athlete (= user) is carried in a ContextVar set by a pure-ASGI
middleware from the signed-cookie session. `repo` reads `current_athlete_id()`
so its functions stay user-agnostic. With auth disabled (local single-user mode)
the contextvar defaults to `DEFAULT_ATHLETE_ID`, so the app and the existing
tests behave exactly as before.

NOTE: this MUST be pure-ASGI middleware, not BaseHTTPMiddleware — the latter runs
the endpoint in a different task and the contextvar would not propagate. Starlette
copies the context into the sync-endpoint threadpool, so a value set here is
visible inside repo.
"""

from __future__ import annotations

from contextvars import ContextVar

from fastapi import HTTPException

from .config import get_settings

_ctx_athlete_id: ContextVar[int | None] = ContextVar("current_athlete_id", default=None)


def set_current_athlete_id(athlete_id: int | None):
    """Set the current athlete for this context (returns a reset token)."""
    return _ctx_athlete_id.set(athlete_id)


def reset_current_athlete_id(token) -> None:
    _ctx_athlete_id.reset(token)


def current_athlete_id() -> int:
    """The current user's athlete id. Falls back to the default when auth is off;
    raises 401 when auth is required and nobody is logged in."""
    aid = _ctx_athlete_id.get()
    if aid is not None:
        return aid
    s = get_settings()
    if not s.auth_required:
        return s.default_athlete_id
    raise HTTPException(status_code=401, detail="Not authenticated")


def maybe_current_athlete_id() -> int | None:
    """The current athlete id without raising (None when unauthenticated)."""
    return _ctx_athlete_id.get()


def is_allowed(strava_athlete_id: int) -> bool:
    """Whether this Strava athlete may log in (allowlist; empty = allow all)."""
    allow = get_settings().allowed_strava_id_set
    return not allow or strava_athlete_id in allow


class AuthContextMiddleware:
    """Pure-ASGI middleware: resolve the current athlete from the session and put
    it in the contextvar for the duration of the request."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        # A native client authenticates with `Authorization: Bearer <token>`;
        # otherwise fall back to the signed-cookie session. Bearer wins when present.
        aid = _athlete_id_from_bearer(scope)
        if aid is None:
            session = scope.get("session") or {}
            aid = session.get("athlete_id")
        if aid is None and not get_settings().auth_required:
            aid = get_settings().default_athlete_id
        token = _ctx_athlete_id.set(aid)
        try:
            await self.app(scope, receive, send)
        finally:
            _ctx_athlete_id.reset(token)


def _athlete_id_from_bearer(scope) -> int | None:
    """Resolve a Bearer token from the request headers to an athlete id, if any."""
    for k, v in scope.get("headers") or []:
        if k == b"authorization":
            value = v.decode("latin-1")
            if value.lower().startswith("bearer "):
                from . import repo  # lazy: avoids repo↔auth import cycle at load

                return repo.athlete_id_for_bearer(value[7:].strip())
            break
    return None
