"""Recovery-data ingestion from Health Auto Export (athlete's own phone).

The app POSTs {"data": {"metrics": [...]}} on its own schedule with an
Authorization: Bearer header (the existing device-token machinery resolves the
athlete). Return 200 fast and lenient — the app surfaces non-2xx to the user
as automation errors, and delivery timing is never guaranteed."""

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
    for day, fields in days.items():
        repo.upsert_daily_recovery(day, fields)
    if days:
        log.info("Health ingest: %d day(s) upserted (%s)", len(days), ", ".join(sorted(days)))
    return {"ok": True, "days": len(days)}


@router.get("/recovery")
def recovery(days: int = 35) -> dict:
    """Recent recovery rows, newest first (dashboard / debugging)."""
    return {"days": repo.recent_recovery(days=max(1, min(days, 365)))}
