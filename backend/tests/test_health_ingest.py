"""Health Auto Export ingestion: parsing, upsert idempotency, endpoint,
and recovery-aware readiness modifiers."""

from datetime import date, timedelta

from starlette.testclient import TestClient

from app import health_ingest, readiness, repo
from app.main import app

TODAY = date.today()


def _payload(**overrides) -> dict:
    """Realistic Summarize-ON payload shaped per the research fixtures."""
    d = TODAY.isoformat()
    y = (TODAY - timedelta(days=1)).isoformat()
    base = {
        "data": {
            "metrics": [
                {
                    "name": "sleep_analysis", "units": "hr",
                    "data": [{
                        "date": f"{d} 07:41:00 -0400",
                        "asleep": 0,
                        "sleepStart": f"{y} 23:12:00 -0400",
                        "sleepEnd": f"{d} 07:41:00 -0400",
                        "inBed": 8.1,
                        "core": 3.9, "deep": 1.1, "rem": 1.6, "awake": 0.4,
                        "source": "iPhone|Apple Watch",
                    }],
                },
                {"name": "heart_rate_variability", "units": "ms",
                 "data": [{"qty": 62.4, "date": f"{d} 00:00:00 -0400"}]},
                {"name": "resting_heart_rate", "units": "bpm",
                 "data": [{"qty": 46, "date": f"{d} 00:00:00 -0400"}]},
                {"name": "weight_body_mass", "units": "lb",
                 "data": [{"qty": 176.4, "date": f"{d} 06:00:00 -0400"}]},
                {"name": "basal_body_temperature", "units": "degC", "data": []},
            ],
            "workouts": [],
        }
    }
    base.update(overrides)
    return base


def test_parse_payload_core_shapes():
    days = health_ingest.parse_payload(_payload())
    row = days[TODAY.isoformat()]
    assert row["sleep_h"] == 6.6  # core+deep+rem (asleep=0 with stage tracking)
    assert row["deep_h"] == 1.1 and row["rem_h"] == 1.6
    assert row["hrv_ms"] == 62.4 and row["rhr_bpm"] == 46
    assert row["weight_kg"] == 80.01  # lb → kg
    assert row["sleep_end"].startswith(TODAY.isoformat())


def test_parse_date_variants():
    assert health_ingest.parse_date("2026-07-13 07:41:00 -0400") is not None
    assert health_ingest.parse_date("2026-07-13 7:41:05 AM -0400") is not None
    assert health_ingest.parse_date("garbage") is None
    # totalSleep wins over stages when present
    rec = {"totalSleep": 7.2, "core": 3.0, "deep": 1.0, "rem": 1.0, "asleep": 0}
    assert health_ingest._sleep_hours(rec) == 7.2


def test_ingest_endpoint_upserts_and_dedupes():
    with TestClient(app) as c:
        r = c.post("/api/health/ingest", json=_payload()).json()
        assert r["ok"] is True and r["days"] == 1
        assert r["parsed"]["records"] == 4 and r["parsed"]["unknown_metrics"] == []
        # Re-send (overlapping window) with a fuller sleep record → same row updated.
        p2 = _payload()
        p2["data"]["metrics"][0]["data"][0]["rem"] = 2.0
        c.post("/api/health/ingest", json=p2)
        rows = c.get("/api/health/recovery").json()["days"]
        assert len(rows) == 1
        assert rows[0]["rem_h"] == 2.0
        assert rows[0]["sleep_h"] == 7.0  # recomputed with fuller stages
        assert rows[0]["hrv_ms"] == 62.4  # untouched fields survive the merge

        # Garbage in → polite 200, nothing stored extra.
        assert c.post("/api/health/ingest", content=b"not json",
                      headers={"Content-Type": "application/json"}).json()["ok"] is False
        r0 = c.post("/api/health/ingest", json={"data": {"metrics": [{"bogus": 1}]}}).json()
        assert r0["ok"] is True and r0["days"] == 0


def _metrics_flat(week_tss: float = 400.0) -> list[dict]:
    per = week_tss / 7.0
    return [{"date": (TODAY - timedelta(days=a)).isoformat(),
             "tss": per, "ctl": 60.0, "atl": 55.0, "tsb": 0.0}
            for a in range(42, 0, -1)]


def _recovery(days: int = 10, *, hrv=60.0, rhr=46.0, sleep=7.5, latest: dict | None = None):
    rows = []
    for a in range(days, 0, -1):
        rows.append({"date": (TODAY - timedelta(days=a)).isoformat(),
                     "sleep_h": sleep, "hrv_ms": hrv, "rhr_bpm": rhr})
    last = {"date": TODAY.isoformat(), "sleep_h": sleep, "hrv_ms": hrv, "rhr_bpm": rhr}
    last.update(latest or {})
    rows.append(last)
    return list(reversed(rows))  # newest first, like repo.recent_recovery


def test_readiness_downgrades_on_suppressed_hrv():
    out = readiness.compute(_metrics_flat(), today=TODAY,
                            recovery=_recovery(latest={"hrv_ms": 40.0}))
    assert (out["call"], out["level"]) == ("easy", "amber")
    assert any("HRV suppressed" in r for r in out["reasons"])


def test_readiness_downgrades_on_short_sleep_and_high_rhr():
    out = readiness.compute(_metrics_flat(), today=TODAY,
                            recovery=_recovery(latest={"sleep_h": 4.9, "rhr_bpm": 53.0}))
    assert out["call"] == "easy"
    joined = " ".join(out["reasons"])
    assert "Short sleep" in joined and "Resting HR elevated" in joined


def test_readiness_ignores_stale_or_normal_recovery():
    # Normal values → still green.
    out = readiness.compute(_metrics_flat(), today=TODAY, recovery=_recovery())
    assert out["call"] == "hard"
    # Stale rows (phone stopped pushing 5 days ago) say nothing.
    stale = [r for r in _recovery(latest={"hrv_ms": 30.0})
             if r["date"] <= (TODAY - timedelta(days=5)).isoformat()]
    out = readiness.compute(_metrics_flat(), today=TODAY, recovery=stale)
    assert out["call"] == "hard"
