"""Fitness-test library: compute math, apply→profile cascade, scheduling, prefill."""

from starlette.testclient import TestClient

from app import fitness_tests, repo
from app.main import app


# --- pure compute math -----------------------------------------------------------

def test_compute_formulas():
    assert fitness_tests.ftp_20min(250) == {"ftp": 238}            # round(0.95*250)=237.5→238
    assert fitness_tests.swim_css(400, 190) == {"css_swim": 105}    # (400-190)/2
    run = fitness_tests.run_lthr_tt(distance_m=7000, time_s=1800, avg_hr_last20=171)
    assert run["threshold_hr"] == 171
    assert run["threshold_pace_run"] == round(1800 / 7.0)           # sec/km


def test_catalog_hides_internal_lambda():
    cat = fitness_tests.catalog()
    assert {t["slug"] for t in cat} == {"bike-ftp-20", "run-lthr-30", "swim-css-400-200"}
    assert all("_fn" not in t for t in cat)


# --- record → apply updates the right athlete field ------------------------------

def test_record_and_apply_sets_threshold():
    with TestClient(app) as c:
        rec = c.post("/api/tests/result",
                     json={"test_slug": "bike-ftp-20", "inputs": {"avg_power_w": 250}}).json()
        assert rec["result"] == {"ftp": 238}
        assert rec["applied"] is False

        applied = c.post(f"/api/tests/result/{rec['id']}/apply").json()
        assert applied["applied"] is True
        # The athlete profile now carries the measured FTP.
        prof = c.get("/api/athlete").json()["profile"]
        assert prof["ftp"] == 238


def test_due_flag_and_history():
    with TestClient(app) as c:
        # No swim test yet → due.
        before = {t["slug"]: t for t in c.get("/api/tests").json()["tests"]}
        assert before["swim-css-400-200"]["due"] is True
        assert before["swim-css-400-200"]["last_tested"] is None

        c.post("/api/tests/result",
               json={"test_slug": "swim-css-400-200", "date": "2026-06-27",
                     "inputs": {"t400_s": 400, "t200_s": 190}})
        after = {t["slug"]: t for t in c.get("/api/tests").json()["tests"]}
        assert after["swim-css-400-200"]["last_tested"] == "2026-06-27"
        assert after["swim-css-400-200"]["due"] is False  # just tested

        hist = c.get("/api/tests/results").json()["results"]
        assert any(r["test_slug"] == "swim-css-400-200" for r in hist)


# --- test workout shows in the plan / plan.itw (→ exports + iOS) -----------------

def test_schedule_adds_workout_to_plan_and_itw():
    with TestClient(app) as c:
        c.post("/api/plan/generate?use_llm=false")  # need an active plan
        sched = c.post("/api/tests/bike-ftp-20/schedule", json={"date": "2026-07-10"})
        assert sched.status_code == 200
        wk = sched.json()
        assert wk["intensity"] == "test" and wk["sport"] == "Bike"

        # Appears in the plan and in the .itw the iOS app fetches.
        workouts = c.get("/api/plan").json()["workouts"]
        test_wk = [w for w in workouts if w.get("intensity") == "test"]
        assert test_wk and test_wk[0]["date"] == "2026-07-10"

        itw = c.get("/api/export/plan.itw").json()
        assert any(w.get("title", "").endswith("(test)") for w in itw["workouts"])


def test_to_workout_steps_are_valid():
    wk = fitness_tests.to_workout("swim-css-400-200")
    assert wk["sport"] == "Swim"
    # has two open-target TT steps with distances
    tt = [s for s in wk["steps"] if s.get("distance_m") in (400, 200)]
    assert len(tt) == 2
    assert all(s["target"]["type"] == "open" for s in tt)


def test_prefill_maps_bike_skips_swim():
    repo.upsert_activities([{
        "id": 9001, "type": "Ride", "sport_type": "Ride",
        "start_date_local": "2026-06-20T08:00:00Z", "moving_time": 1300,
        "distance": 12000, "average_watts": 240, "weighted_average_watts": 250,
    }])
    with TestClient(app) as c:
        bike = c.get("/api/tests/bike-ftp-20/prefill").json()["candidates"]
        assert bike and "avg_power_w" in bike[0]["inputs"]
        swim = c.get("/api/tests/swim-css-400-200/prefill").json()["candidates"]
        assert swim == []


def test_prefill_returns_most_recent_first():
    # An older and a newer ride; prefill should surface the newer one first.
    repo.upsert_activities([
        {"id": 8001, "type": "Ride", "sport_type": "Ride",
         "start_date_local": "2026-01-10T08:00:00Z", "moving_time": 1300,
         "distance": 12000, "average_watts": 200, "weighted_average_watts": 205},
        {"id": 8002, "type": "Ride", "sport_type": "Ride",
         "start_date_local": "2026-06-25T08:00:00Z", "moving_time": 1300,
         "distance": 12000, "average_watts": 250, "weighted_average_watts": 260},
    ])
    with TestClient(app) as c:
        cands = c.get("/api/tests/bike-ftp-20/prefill").json()["candidates"]
        assert cands[0]["date"] == "2026-06-25"        # newest first
        assert cands[0]["inputs"]["avg_power_w"] == 260
        dates = [x["date"] for x in cands]
        assert dates == sorted(dates, reverse=True)     # descending overall
