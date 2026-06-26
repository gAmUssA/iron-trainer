"""Strava OAuth + sync endpoints."""

from __future__ import annotations

import secrets

from fastapi import APIRouter, HTTPException, Query, Request
from fastapi.responses import RedirectResponse

from .. import auth, repo, services, strava
from ..config import get_settings

router = APIRouter(prefix="/api/strava", tags=["strava"])


@router.get("/connect")
def connect(request: Request) -> RedirectResponse:
    """Begin Strava OAuth — also the login entry point when auth is required."""
    s = get_settings()
    if not s.strava_configured:
        raise HTTPException(400, "Strava client ID/secret not configured in .env")
    state = secrets.token_urlsafe(16)
    request.session["oauth_state"] = state
    return RedirectResponse(strava.authorize_url(state=state))


@router.get("/callback")
def callback(
    request: Request,
    code: str | None = Query(None),
    state: str | None = Query(None),
    error: str | None = Query(None),
) -> RedirectResponse:
    if error or not code:
        raise HTTPException(400, f"Strava authorization failed: {error or 'no code'}")
    s = get_settings()
    if s.auth_required and (not state or state != request.session.get("oauth_state")):
        raise HTTPException(400, "Invalid OAuth state")
    request.session.pop("oauth_state", None)

    token = strava.exchange_code(code)
    strava_id = (token.get("athlete") or {}).get("id")
    ath = token.get("athlete") or {}
    name = " ".join(filter(None, [ath.get("firstname"), ath.get("lastname")])) or None

    if s.auth_required:
        if strava_id is None or not auth.is_allowed(strava_id):
            raise HTTPException(403, "This Strava account is not allowed on this instance.")
        athlete_id = repo.find_or_create_athlete(strava_id, name)
        request.session["athlete_id"] = athlete_id
    else:
        # Local single-user mode: attach to the default athlete (no new users).
        athlete_id = s.default_athlete_id

    repo.save_tokens(athlete_id, token)
    dest = s.cors_origin_list[0] if s.cors_origin_list else "/"
    return RedirectResponse(f"{dest}/?connected=1")


@router.post("/sync")
def sync(full: bool = Query(False, description="Full history backfill vs incremental")) -> dict:
    try:
        return services.run_sync(full=full)
    except services.NotConnected as e:
        raise HTTPException(409, str(e)) from e


@router.post("/dedup")
def dedup(
    fetch: bool = Query(True, description="Look up device names from Strava (Apple Watch / Edge)"),
    limit: int = Query(100, description="Max device lookups this run (resumable)"),
) -> dict:
    """Re-run activity de-duplication on the existing data (no full re-sync).

    Bounded per call (`limit`) so it stays responsive and survives Strava rate
    limits; click again to continue — device names are cached.
    """
    token = None
    if fetch:
        try:
            token = services.valid_access_token()
        except services.NotConnected as e:
            raise HTTPException(409, str(e)) from e
    stats = services.deduplicate(token=token, fetch_details=fetch, max_fetches=limit or None)
    stats["metrics_days"] = repo.rebuild_metrics()
    return stats
