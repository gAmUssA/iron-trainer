"""Recovery-data ingestion from Health Auto Export (athlete's own phone).

The app POSTs {"data": {"metrics": [...]}} on its own schedule with an
Authorization: Bearer header (the existing device-token machinery resolves the
athlete). Parseable-or-not payloads get a fast 200 (the app surfaces non-2xx to
the user as automation errors); auth failures deliberately stay 401 —
a misconfigured token SHOULD be visible in the app."""

from __future__ import annotations

from fastapi import APIRouter, Request

from .. import health_ingest, repo
from ..logging_config import get_logger

router = APIRouter(prefix="/api/health", tags=["health"])
log = get_logger("health")


@router.post("/ingest")
async def ingest(request: Request) -> dict:
    try:
        payload = await request.json()
    except Exception:  # noqa: BLE001 — malformed body: acknowledge, don't retry-loop
        return {"ok": False, "error": "invalid JSON", "days": 0}
    days = health_ingest.parse_payload(payload if isinstance(payload, dict) else {})
    stored = 0
    for day, fields in days.items():
        try:
            repo.upsert_daily_recovery(day, fields)
            stored += 1
        except Exception as e:  # noqa: BLE001 — one bad day must not 500 the batch
            log.warning("Recovery upsert failed for %s: %s", day, e)
    if days:
        log.info("Health ingest: %d day(s) upserted (%s)", len(days), ", ".join(sorted(days)))
    return {"ok": True, "days": stored}


@router.get("/recovery")
def recovery(days: int = 35) -> dict:
    """Recent recovery rows, newest first (dashboard / debugging)."""
    return {"days": repo.recent_recovery(days=max(1, min(days, 365)))}
