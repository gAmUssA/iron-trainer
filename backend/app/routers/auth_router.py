"""Auth/session endpoints for multi-user mode."""

from __future__ import annotations

from fastapi import APIRouter, Request

from .. import auth, repo
from ..config import get_settings

router = APIRouter(prefix="/api", tags=["auth"])


@router.get("/me")
def me() -> dict:
    """Current auth state — drives the frontend login gate."""
    s = get_settings()
    aid = auth.maybe_current_athlete_id()
    out: dict = {"authenticated": aid is not None, "auth_required": s.auth_required, "athlete": None}
    if aid is not None:
        a = repo.get_athlete()
        out["athlete"] = {"name": a.get("name"), "strava_athlete_id": a.get("strava_athlete_id")}
    return out


@router.post("/auth/logout")
def logout(request: Request) -> dict:
    request.session.clear()
    return {"ok": True}
