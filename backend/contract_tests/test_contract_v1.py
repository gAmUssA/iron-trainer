"""Contract v1: the endpoints the first Quarkus verticals must reproduce.
Assertions target the CONTRACT (shape + invariants), not incidental values."""

import httpx


def test_health(api: httpx.Client):
    body = api.get("/api/health").json()
    assert body["status"] == "ok"


def test_status_shape(api: httpx.Client):
    body = api.get("/api/status").json()
    assert {"race", "authenticated", "auth_required"} <= set(body)
    assert {"name", "date"} <= set(body["race"])


def test_profile_roundtrip(seeded: httpx.Client):
    body = seeded.get("/api/athlete").json()
    p = body["profile"]
    assert p["ftp"] == 228 and p["threshold_hr"] == 160


def test_zones_from_lthr(seeded: httpx.Client):
    z = seeded.get("/api/athlete/zones").json()
    assert z["basis"] == "lthr"
    zones = {r["zone"]: r for r in z["zones"]}
    assert zones["Z2"]["low"] == round(160 * 0.81)
    assert zones["Z5"]["high"] <= 182  # capped by max HR


def test_plan_has_full_season(seeded: httpx.Client):
    plan = seeded.get("/api/plan").json()
    assert plan["plan"] is not None
    assert len(plan["plan"]["weeks"]) >= 8
    assert len(plan["workouts"]) > 20
    sports = {w["sport"] for w in plan["workouts"]}
    assert {"Swim", "Bike", "Run"} <= sports


def test_races_catalog(api: httpx.Client):
    races = api.get("/api/races").json()["races"]
    assert len(races) >= 40
    assert all({"name", "date", "distance"} <= set(r) for r in races[:5])


def test_readiness_today_contract(seeded: httpx.Client):
    r = seeded.get("/api/metrics/readiness/today").json()
    assert r["status"] in ("ok", "insufficient_data")
    assert "reasons" in r and isinstance(r["reasons"], list)


def test_pmc_windowing(seeded: httpx.Client):
    body = seeded.get("/api/metrics/pmc?days=30").json()
    assert {"days", "window_days", "total_days"} <= set(body)
    assert body["window_days"] == 30
    assert len(body["days"]) <= 30


def test_export_fit_zwo_itw(seeded: httpx.Client):
    workouts = seeded.get("/api/plan").json()["workouts"]
    bike = next(w for w in workouts if w["sport"] == "Bike")
    fit = seeded.get(f"/api/export/workout/{bike['id']}.fit")
    assert fit.status_code == 200
    assert fit.content[8:12] == b".FIT"  # FIT header magic
    zwo = seeded.get(f"/api/export/workout/{bike['id']}.zwo")
    assert zwo.status_code == 200 and b"<workout_file>" in zwo.content
    itw = seeded.get(f"/api/export/workout/{bike['id']}.itw")
    assert itw.status_code == 200
    assert itw.json().get("schema_version") == 1


def test_checkin_narrates(seeded: httpx.Client):
    r = seeded.post("/api/plan/checkin?use_llm=false").json()
    assert r["status"] == "ok"
    assert isinstance(r["story"], list) and len(r["story"]) >= 3


def test_nutrition_daily_shape(seeded: httpx.Client):
    n = seeded.get("/api/nutrition/daily").json()
    assert isinstance(n, dict) and n  # per-day fueling entries exist
