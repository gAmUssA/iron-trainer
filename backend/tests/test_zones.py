"""HR zones: math, endpoint, and zone-aware plan prescriptions."""

from starlette.testclient import TestClient

from app import zones
from app.main import app


def test_lthr_zones_bands():
    out = zones.hr_zones(160)
    assert out["basis"] == "lthr"
    z = {r["zone"]: r for r in out["zones"]}
    assert z["Z2"]["low"] == round(160 * 0.81) and z["Z2"]["high"] == round(160 * 0.89)
    assert z["Z4"]["low"] == round(160 * 0.94) and z["Z4"]["high"] == round(160 * 0.99)


def test_zone5_capped_by_max_hr():
    out = zones.hr_zones(160, 165)
    z5 = out["zones"][-1]
    assert z5["high"] == 165  # 160×1.06=170 would exceed max HR


def test_max_hr_fallback_basis():
    out = zones.hr_zones(None, 180)
    assert out["basis"] == "max_hr"
    z2 = out["zones"][1]
    assert (z2["low"], z2["high"]) == (108, 126)  # 60–70% of 180
    assert zones.hr_zones(None, None) == {"basis": None, "zones": []}


def test_intensity_maps_to_zones():
    assert zones.zone_label("endurance") == "Z2"
    assert zones.zone_label("vo2") == "Z5"
    assert zones.hr_range_for_intensity("endurance", 160) == (round(160 * 0.81), round(160 * 0.89))
    assert zones.hr_range_for_intensity("endurance", None) is None


def test_zones_endpoint():
    with TestClient(app) as c:
        c.put("/api/athlete/profile", json={"threshold_hr": 162, "max_hr": 182})
        out = c.get("/api/athlete/zones").json()
        assert out["basis"] == "lthr"
        assert len(out["zones"]) == 5
        assert out["threshold_hr"] == 162


def test_plan_prescribes_hr_when_power_and_pace_missing():
    """No FTP / run pace → bike & run steps get HR-zone targets, not empty ones."""
    with TestClient(app) as c:
        c.put("/api/athlete/profile", json={"threshold_hr": 160, "max_hr": 185})
        c.post("/api/plan/generate?use_llm=false")
        workouts = c.get("/api/plan").json()["workouts"]
        bikes = [w for w in workouts if w["sport"] == "Bike"]
        runs = [w for w in workouts if w["sport"] == "Run"]
        assert bikes and runs
        for w in bikes[:3] + runs[:3]:
            main = w["steps"][1]  # warmup/main/cooldown
            assert main["target"]["type"] == "hr"
            assert main["target"]["low"] and main["target"]["high"]
        # Zone label + bpm range surface in the description for every sport.
        assert any("Z2" in (w["description"] or "") and "bpm" in (w["description"] or "")
                   for w in workouts)


def test_plan_keeps_power_pace_when_thresholds_present():
    with TestClient(app) as c:
        c.put("/api/athlete/profile",
              json={"ftp": 228, "threshold_pace_run": 300, "threshold_hr": 160})
        c.post("/api/plan/generate?use_llm=false")
        workouts = c.get("/api/plan").json()["workouts"]
        bike_main = next(w for w in workouts if w["sport"] == "Bike")["steps"][1]
        run_main = next(w for w in workouts if w["sport"] == "Run")["steps"][1]
        assert bike_main["target"]["type"] == "power"
        assert run_main["target"]["type"] == "pace"
        # Zone label still annotates the description alongside power/pace targets.
        assert any("Z" in (w["description"] or "") for w in workouts)
