"""Strava API client: OAuth + activity fetching (summary data only)."""

from __future__ import annotations

import time
from urllib.parse import urlencode

import httpx

from .config import get_settings

AUTHORIZE_URL = "https://www.strava.com/oauth/authorize"
TOKEN_URL = "https://www.strava.com/oauth/token"
API_BASE = "https://www.strava.com/api/v3"

# We need full activity history (incl. private) to assess fitness.
SCOPE = "read,activity:read_all"


def authorize_url(state: str = "iron-trainer") -> str:
    s = get_settings()
    params = {
        "client_id": s.strava_client_id,
        "redirect_uri": s.strava_redirect_uri,
        "response_type": "code",
        "scope": SCOPE,
        "approval_prompt": "auto",
        "state": state,
    }
    return f"{AUTHORIZE_URL}?{urlencode(params)}"


def exchange_code(code: str) -> dict:
    s = get_settings()
    resp = httpx.post(
        TOKEN_URL,
        data={
            "client_id": s.strava_client_id,
            "client_secret": s.strava_client_secret,
            "code": code,
            "grant_type": "authorization_code",
        },
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json()


def refresh_access_token(refresh_token: str) -> dict:
    s = get_settings()
    resp = httpx.post(
        TOKEN_URL,
        data={
            "client_id": s.strava_client_id,
            "client_secret": s.strava_client_secret,
            "grant_type": "refresh_token",
            "refresh_token": refresh_token,
        },
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json()


def fetch_activity_detail(access_token: str, activity_id: int) -> dict:
    """Fetch a single detailed activity (includes `device_name`)."""
    headers = {"Authorization": f"Bearer {access_token}"}
    resp = httpx.get(f"{API_BASE}/activities/{activity_id}", headers=headers, timeout=30)
    resp.raise_for_status()
    return resp.json()


def fetch_activities(access_token: str, *, after: int | None = None) -> list[dict]:
    """Fetch all activity summaries, paginating. `after` = unix seconds for
    incremental sync (only activities started after that time)."""
    out: list[dict] = []
    page = 1
    headers = {"Authorization": f"Bearer {access_token}"}
    with httpx.Client(base_url=API_BASE, headers=headers, timeout=60) as client:
        while True:
            params: dict = {"per_page": 200, "page": page}
            if after:
                params["after"] = after
            resp = client.get("/athlete/activities", params=params)
            resp.raise_for_status()
            batch = resp.json()
            if not batch:
                break
            out.extend(batch)
            if len(batch) < 200:
                break
            page += 1
            time.sleep(0.2)  # be gentle with rate limits
    return out
