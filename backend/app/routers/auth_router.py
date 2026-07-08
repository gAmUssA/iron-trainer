"""Auth/session endpoints for multi-user mode."""

from __future__ import annotations

import time
from collections import deque

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


# Brute-force friction for the unauthenticated claim endpoint: pairing codes are
# 32-bit with a 10-min TTL, so guessing needs millions of attempts — deny that
# rate outright. Keyed per client so one bad actor (or fat-fingered code) can't
# lock out everyone. In-process (single-worker deploy); failures only.
_CLAIM_WINDOW_S = 60.0
_CLAIM_MAX_FAILURES = 10
_claim_failures: dict[str, deque[float]] = {}


def _client_key(request: Request) -> str:
    """Throttle key for the requester. Railway/uvicorn runs behind a proxy
    without --proxy-headers, so request.client.host is the proxy — key on the
    first X-Forwarded-For hop when present. XFF is spoofable, but spoofing only
    spreads an attacker across keys; the throttle is friction on top of a
    32-bit/10-min code space, not the security boundary."""
    xff = request.headers.get("x-forwarded-for")
    if xff:
        return xff.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


def _claim_throttled(client: str) -> bool:
    now = time.monotonic()
    q = _claim_failures.setdefault(client, deque())
    while q and now - q[0] > _CLAIM_WINDOW_S:
        q.popleft()
    # Drop other clients' empty queues so the dict can't grow unbounded.
    for k in [k for k, v in _claim_failures.items() if not v and k != client]:
        del _claim_failures[k]
    return len(q) >= _CLAIM_MAX_FAILURES


@router.post("/device/claim")
def device_claim(body: ClaimRequest, request: Request) -> dict:
    """Exchange a pairing code for a long-lived bearer token. Unauthenticated —
    the code itself is the credential."""
    client = _client_key(request)
    if _claim_throttled(client):
        raise HTTPException(status_code=429, detail="Too many attempts — try again in a minute.")
    result = repo.claim_pairing_code(body.code.strip(), device_name=body.device_name)
    if result is None:
        _claim_failures[client].append(time.monotonic())
        log.warning("Rejected device claim with an invalid/expired pairing code.")
        raise HTTPException(status_code=400, detail="Invalid or expired pairing code.")
    # Success clears the requester's failures — a fat-fingered code followed by
    # the right one shouldn't self-lock the user out for a minute.
    _claim_failures.pop(client, None)
    log.info("Device %r paired to athlete %s.", body.device_name or "(unnamed)",
             result["athlete"].get("strava_athlete_id"))
    return result
