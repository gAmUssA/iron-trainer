"""Auth/session endpoints for multi-user mode."""

from __future__ import annotations

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel

from .. import auth, repo
from ..config import get_settings
from ..logging_config import get_logger

router = APIRouter(prefix="/api", tags=["auth"])
log = get_logger("auth")


class PairingCodeRequest(BaseModel):
    name: str | None = None


class ClaimRequest(BaseModel):
    code: str
    device_name: str | None = None


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


@router.delete("/device/tokens")
def revoke_device_tokens() -> dict:
    """Revoke all paired devices (bearer tokens) for the logged-in athlete. Called
    by the iOS app on sign-out; also available to cut off a lost device."""
    aid = auth.current_athlete_id()
    n = repo.revoke_device_tokens()
    log.info("Athlete %s revoked %d device token(s).", aid, n)
    return {"revoked": n}


@router.post("/device/pairing-code")
def device_pairing_code(body: PairingCodeRequest | None = None) -> dict:
    """Mint a short-lived pairing code for the logged-in athlete (the web UI shows
    it as a QR). In local no-login mode this pairs to the default athlete."""
    aid = auth.current_athlete_id()  # 401 when auth_required and not logged in
    out = repo.create_pairing_code(aid, name=(body.name if body else None))
    log.info("Issued device pairing code for athlete %s (expires %s).", aid, out["expires_at"])
    return out


@router.post("/device/claim")
def device_claim(body: ClaimRequest) -> dict:
    """Exchange a pairing code for a long-lived bearer token. Unauthenticated —
    the code itself is the credential."""
    result = repo.claim_pairing_code(body.code.strip(), device_name=body.device_name)
    if result is None:
        log.warning("Rejected device claim with an invalid/expired pairing code.")
        raise HTTPException(status_code=400, detail="Invalid or expired pairing code.")
    log.info("Device %r paired to athlete %s.", body.device_name or "(unnamed)",
             result["athlete"].get("strava_athlete_id"))
    return result
