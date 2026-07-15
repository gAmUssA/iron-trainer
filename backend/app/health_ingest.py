"""Parse Health Auto Export REST payloads into daily recovery rows.

Payload contract (see docs/research/health-auto-export-rest-api.md):
    {"data": {"metrics": [{"name", "units", "data": [{...}]}], "workouts": [...]}}

Everything here is deliberately lenient — units follow user preferences, dates
are NOT ISO 8601 (`yyyy-MM-dd HH:mm:ss Z`, sometimes 12-hour with a U+202F
narrow space), metrics may arrive with empty data arrays, and overlapping
windows are re-sent by design. Parse what we can, ignore the rest, upsert.
"""

from __future__ import annotations

from datetime import datetime

from .logging_config import get_logger

log = get_logger("health_ingest")

_DATE_FORMATS = (
    "%Y-%m-%d %H:%M:%S %z",     # documented: "2026-07-13 07:41:00 -0400"
    "%Y-%m-%d %I:%M:%S %p %z",  # 12-hour locale variant
    "%Y-%m-%d %H:%M %z",
)


def parse_date(raw: str | None) -> datetime | None:
    if not raw or not isinstance(raw, str):
        return None
    cleaned = raw.replace("\u202f", " ").strip()  # strip 12-hour narrow no-break space
    for fmt in _DATE_FORMATS:
        try:
            return datetime.strptime(cleaned, fmt)
        except ValueError:
            continue
    try:
        return datetime.fromisoformat(cleaned)
    except ValueError:
        return None


def _local_day(raw: str | None) -> str | None:
    """Calendar day in the timestamp's OWN embedded offset — never server tz."""
    dt = parse_date(raw)
    return dt.date().isoformat() if dt else None


def _to_kg(qty: float, units: str | None) -> float:
    return round(qty * 0.45359237, 2) if (units or "").lower() in ("lb", "lbs") else qty


def _to_celsius(qty: float, units: str | None) -> float:
    return round((qty - 32) * 5 / 9, 2) if "f" in (units or "").lower() else qty


def _sleep_hours(rec: dict) -> float | None:
    """Resolution order established by real integrations: totalSleep →
    core+deep+rem (Watch stage tracking often reports asleep=0) → asleep → inBed."""
    total = rec.get("totalSleep")
    if isinstance(total, (int, float)) and total > 0:
        return float(total)
    stages = [rec.get(k) for k in ("core", "deep", "rem")]
    if any(isinstance(s, (int, float)) and s > 0 for s in stages):
        return float(sum(s for s in stages if isinstance(s, (int, float))))
    for k in ("asleep", "inBed"):
        v = rec.get(k)
        if isinstance(v, (int, float)) and v > 0:
            return float(v)
    return None


def _num(v) -> float | None:
    return float(v) if isinstance(v, (int, float)) else None


def parse_payload(payload: dict, stats: dict | None = None) -> dict[str, dict]:
    """Reduce a Health Auto Export payload to {local_date: partial recovery row}.

    Multiple samples on the same day average (HRV/RHR arrive per-day when the
    automation aggregates by days, but be tolerant of raw samples too)."""
    metrics = ((payload or {}).get("data") or {}).get("metrics") or []
    if stats is None:
        stats = {}
    stats.update({"metrics_seen": [], "records": 0, "bad_dates": 0,
                  "unknown_metrics": [], "bad_date_samples": []})
    days: dict[str, dict] = {}
    sums: dict[tuple[str, str], list[float]] = {}

    def bucket(day: str | None) -> dict | None:
        if not day:
            return None
        return days.setdefault(day, {})

    for m in metrics:
        if not isinstance(m, dict):
            continue
        name = m.get("name")
        units = m.get("units")
        if name:
            stats["metrics_seen"].append(str(name))
        for rec in m.get("data") or []:
            if not isinstance(rec, dict):
                continue
            stats["records"] += 1
            if name == "sleep_analysis":
                # Key the night by wake-up day (sleepEnd), falling back to date.
                raw_day = rec.get("sleepEnd") or rec.get("date")
                day = _local_day(raw_day)
                row = bucket(day)
                if row is None:
                    stats["bad_dates"] += 1
                    if len(stats["bad_date_samples"]) < 3 and raw_day:
                        stats["bad_date_samples"].append(str(raw_day)[:40])
                    continue
                sleep = _sleep_hours(rec)
                if sleep is not None:
                    row["sleep_h"] = round(sleep, 2)
                for src, dst in (("deep", "deep_h"), ("rem", "rem_h"), ("awake", "awake_h")):
                    v = _num(rec.get(src))
                    if v is not None:
                        row[dst] = round(v, 2)
                for src, dst in (("sleepStart", "sleep_start"), ("sleepEnd", "sleep_end")):
                    dt = parse_date(rec.get(src))
                    if dt:
                        row[dst] = dt.isoformat()
                continue

            raw_day = rec.get("date") or rec.get("startDate")
            day = _local_day(raw_day)
            qty = _num(rec.get("qty"))
            if day is None:
                stats["bad_dates"] += 1
                if len(stats["bad_date_samples"]) < 3 and raw_day:
                    stats["bad_date_samples"].append(str(raw_day)[:40])
                continue
            if qty is None:
                continue
            field = {
                "heart_rate_variability": "hrv_ms",
                "resting_heart_rate": "rhr_bpm",
                "weight_body_mass": "weight_kg",
                "vo2max": "vo2max",
                "respiratory_rate": "respiratory_rate",
                "apple_sleeping_wrist_temperature": "wrist_temp_c",
            }.get(str(name))
            if not field:
                if str(name) not in stats["unknown_metrics"]:
                    stats["unknown_metrics"].append(str(name))
                continue  # unrecognized metric — ignore, never fail
            if field == "weight_kg":
                qty = _to_kg(qty, units)
            elif field == "wrist_temp_c":
                qty = _to_celsius(qty, units)
            sums.setdefault((day, field), []).append(qty)

    for (day, field), vals in sums.items():
        row = bucket(day)
        if row is not None:
            row[field] = round(sum(vals) / len(vals), 2)

    return {d: r for d, r in days.items() if r}
