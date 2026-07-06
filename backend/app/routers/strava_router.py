"""Strava OAuth + sync endpoints."""

from __future__ import annotations

import os
import secrets
import tempfile
from urllib.parse import urlencode

import httpx
from fastapi import APIRouter, HTTPException, Query, Request, UploadFile
from fastapi.responses import RedirectResponse

from .. import auth, repo, services, strava
from ..config import get_settings
from ..logging_config import get_logger

router = APIRouter(prefix="/api/strava", tags=["strava"])
log = get_logger("strava")

MAX_UPLOAD_BYTES = 2 * 1024**3  # 2 GB — well above any real Strava export


def _redirect(**params: str) -> RedirectResponse:
    """Send the browser back to the SPA with a query flag, so OAuth outcomes surface
    as a friendly in-app banner instead of a raw JSON error page."""
    s = get_settings()
    dest = s.cors_origin_list[0] if s.cors_origin_list else ""
    return RedirectResponse(f"{dest}/?{urlencode(params)}")


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
    s = get_settings()
    if error or not code:
        # Strava bounced the user back denied/cancelled (e.g. access_denied).
        log.info("Strava authorization returned without a code (error=%s).", error)
        return _redirect(strava_error=error or "no_code")
    if s.auth_required and (not state or state != request.session.get("oauth_state")):
        log.warning("Strava callback with invalid OAuth state.")
        return _redirect(strava_error="invalid_state")
    request.session.pop("oauth_state", None)

    try:
        token = strava.exchange_code(code)
    except httpx.HTTPError as e:
        log.warning("Strava token exchange failed: %s", e)
        return _redirect(strava_error="exchange_failed")
    strava_id = (token.get("athlete") or {}).get("id")
    ath = token.get("athlete") or {}
    name = " ".join(filter(None, [ath.get("firstname"), ath.get("lastname")])) or None

    if s.auth_required:
        if strava_id is None or not auth.is_allowed(strava_id):
            log.warning("Rejected Strava login for athlete id %s (not on allowlist).", strava_id)
            return _redirect(strava_error="not_allowed")
        athlete_id = repo.find_or_create_athlete(strava_id, name)
        request.session["athlete_id"] = athlete_id
        log.info("Strava login: athlete %s (strava id %s) signed in.", athlete_id, strava_id)
    else:
        # Local single-user mode: attach to the default athlete (no new users).
        athlete_id = s.default_athlete_id
        log.info("Strava connected to local default athlete (strava id %s).", strava_id)

    repo.save_tokens(athlete_id, token)
    return _redirect(connected="1")


@router.post("/disconnect")
def disconnect() -> dict:
    """Disconnect Strava and delete the athlete's Strava data (API agreement §7.4):
    revoke access at Strava, then purge synced activities + derived metrics + tokens.
    Returns a deletion summary — the §2.5 written confirmation."""
    aid = auth.current_athlete_id()  # 401 if auth_required and not logged in
    deauthorized = False
    try:
        token = services.valid_access_token()
        strava.deauthorize(token)
        deauthorized = True
    except (services.NotConnected, httpx.HTTPError) as e:
        # Already revoked / token gone — still purge local data.
        log.info("Deauthorize skipped (%s); proceeding to local deletion.", e)
    summary = repo.disconnect_strava()
    log.info("Athlete %s disconnected Strava: deleted %s activities, %s metric days.",
             aid, summary["deleted_activities"], summary["deleted_metrics"])
    return {"deauthorized": deauthorized, **summary,
            "message": "Disconnected. Your Strava activities and derived data have been deleted."}


@router.post("/import")
def import_archive(file: UploadFile) -> dict:
    """Bulk-load history from a user's uploaded Strava GDPR export ZIP. Athlete-scoped;
    works without a live API connection (bypasses the rate-limit/athlete cap)."""
    auth.current_athlete_id()  # 401 if auth_required and not logged in
    tmp = tempfile.NamedTemporaryFile(suffix=".zip", delete=False)
    try:
        # Stream upload to disk (don't hold GBs in RAM), counting bytes so a
        # runaway upload can't fill the container's disk.
        written = 0
        while chunk := file.file.read(1 << 20):
            written += len(chunk)
            if written > MAX_UPLOAD_BYTES:
                raise HTTPException(413, "Archive exceeds the 2 GB upload limit.")
            tmp.write(chunk)
        tmp.close()
        return services.import_archive(tmp.name)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e
    finally:
        try:
            os.unlink(tmp.name)
        except OSError:
            pass


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
