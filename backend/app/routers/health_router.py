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
        log.warning("Health ingest: malformed JSON body (%s bytes)",
                    request.headers.get("content-length", "?"))
        return {"ok": False, "error": "invalid JSON", "days": 0}
    stats: dict = {}
    days = health_ingest.parse_payload(payload if isinstance(payload, dict) else {},
                                       stats=stats)
    stored = 0
    for day, fields in days.items():
        try:
            repo.upsert_daily_recovery(day, fields)
            stored += 1
        except Exception as e:  # noqa: BLE001 — one bad day must not 500 the batch
            log.warning("Recovery upsert failed for %s: %s", day, e)
    # Metric NAMES and counts only — never values; this is health data.
    summary = ("%d metric(s) %s, %d record(s), %d day(s) stored"
               % (len(stats.get("metrics_seen", [])), sorted(set(stats.get("metrics_seen", []))),
                  stats.get("records", 0), stored))
    if days:
        log.info("Health ingest: %s (%s)", summary, ", ".join(sorted(days)))
    else:
        # The silent failure mode that matters: payload arrived but nothing
        # was recognized — wrong metrics selected, or a date format we miss.
        log.warning("Health ingest: nothing stored — %s; unknown_metrics=%s bad_dates=%d samples=%s",
                    summary, stats.get("unknown_metrics"), stats.get("bad_dates", 0),
                    stats.get("bad_date_samples"))
    return {"ok": True, "days": stored,
            "parsed": {"records": stats.get("records", 0),
                       "unknown_metrics": stats.get("unknown_metrics", []),
                       "bad_dates": stats.get("bad_dates", 0)}}


@router.get("/recovery")
def recovery(days: int = 35) -> dict:
    """Recent recovery rows, newest first (dashboard / debugging)."""
    return {"days": repo.recent_recovery(days=max(1, min(days, 365)))}
