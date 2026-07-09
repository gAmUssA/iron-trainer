from datetime import date

from app.planning import service, template
from app.planning.validator import (
    ABS_MAX_WEEK_HOURS,
    MAX_WEEKLY_RAMP,
    validate_season,
    validate_week_workouts,
)

PROFILE = {
    "ftp": 230,
    "threshold_hr": 162,
    "max_hr": 182,
    "threshold_pace_run": 305,
    "css_swim": 100,
    "weekly_hours_target": 8.0,
}


def test_build_season_structure():
    season = template.build_season(
        start=date(2026, 6, 25), race_date=date(2026, 9, 26), weekly_hours=8.0
    )
    weeks = season["weeks"]
    assert len(weeks) >= 12
    # Last two weeks taper.
    assert weeks[-1]["phase"] == "taper"
    assert weeks[-2]["phase"] == "taper"
    # Mondays, 7 days apart.
    assert all(date.fromisoformat(w["week_start"]).weekday() == 0 for w in weeks)


def test_validator_caps_ramp_and_enforces_recovery():
    # A reckless plan: hours doubling each week, no recovery, no taper.
    weeks = [
        {"week_index": i + 1, "week_start": f"2026-0{6 + i // 4}-{1 + i:02d}",
         "phase": "build", "is_recovery": False, "focus": "", "target_hours": 6.0 * (2**i)}
        for i in range(8)
    ]
    season = {"weeks": weeks, "race_date": "2026-09-26"}
    fixed, notes = validate_season(season)
    fixed_weeks = fixed["weeks"]

    # Ramp between consecutive building weeks never exceeds the cap.
    build_hours = [w["target_hours"] for w in fixed_weeks if not w["is_recovery"] and w["phase"] != "taper"]
    for a, b in zip(build_hours, build_hours[1:]):
        assert b <= a * MAX_WEEKLY_RAMP + 0.2
    # A recovery week was inserted.
    assert any(w["is_recovery"] for w in fixed_weeks)
    # Nothing exceeds the absolute ceiling.
    assert all(w["target_hours"] <= ABS_MAX_WEEK_HOURS for w in fixed_weeks)
    # Last two weeks are taper and descending.
    assert fixed_weeks[-1]["phase"] == "taper"
    assert fixed_weeks[-1]["target_hours"] <= fixed_weeks[-2]["target_hours"]
    assert notes  # changes were reported


def test_expand_week_produces_targeted_workouts():
    season = template.build_season(
        start=date(2026, 6, 25), race_date=date(2026, 9, 26), weekly_hours=8.0
    )
    wk = season["weeks"][4]  # a mid build week
    workouts = template.expand_week(wk, PROFILE)
    sports = {w["sport"] for w in workouts}
    assert {"Swim", "Bike", "Run"} <= sports
    bike = next(w for w in workouts if w["sport"] == "Bike")
    main = next(s for s in bike["steps"] if s["type"] == "steady")
    assert main["target"]["unit"] == "W"
    assert main["target"]["low"] and main["target"]["high"]
    # Power targets are a sane fraction of FTP.
    assert 100 < main["target"]["low"] < 300


def test_session_cap_clamps_long_workouts():
    workouts = [{"date": "2026-07-01", "sport": "Run", "intensity": "endurance", "duration_s": 4 * 3600}]
    fixed, notes = validate_week_workouts(workouts)
    assert fixed[0]["duration_s"] == int(2.5 * 3600)  # Run cap
    assert notes


def test_generate_plan_without_llm(monkeypatch):
    # Seed an athlete profile so expansion has thresholds.
    from app import repo

    repo.save_profile(PROFILE)
    result = service.generate_plan(use_llm=False, today=date(2026, 6, 25))
    assert result["plan_id"] >= 1
    assert result["llm_used"] is False
    assert result["weeks"] >= 12
    assert result["workouts"] > 0

    plan = repo.get_active_plan()
    assert plan and plan["status"] == "active"
    workouts = repo.get_workouts(plan["id"])
    assert len(workouts) == result["workouts"]
    # Steps round-trip through storage.
    assert any(w["steps"] for w in workouts)


def test_plan_records_base_weekly_hours_for_staleness_hint():
    """Changing weekly hours must be detectable against the active plan
    (the plan itself is intentionally untouched until regeneration)."""
    from starlette.testclient import TestClient

    from app.main import app

    with TestClient(app) as c:
        c.put("/api/athlete/profile", json={"weekly_hours_target": 6})
        c.post("/api/plan/generate?use_llm=false")
        plan = c.get("/api/plan").json()["plan"]
        assert plan["base_weekly_hours"] == 6.0

        # Saving new hours does NOT touch the plan — the mismatch is the signal.
        c.put("/api/athlete/profile", json={"weekly_hours_target": 10})
        plan = c.get("/api/plan").json()["plan"]
        assert plan["base_weekly_hours"] == 6.0

        c.post("/api/plan/generate?use_llm=false")
        plan = c.get("/api/plan").json()["plan"]
        assert plan["base_weekly_hours"] == 10.0


def test_threshold_change_refreshes_future_workout_targets():
    """Improving fitness must flow into future prescriptions automatically —
    without touching the current week or completion history."""
    from starlette.testclient import TestClient

    from app.main import app

    with TestClient(app) as c:
        c.put("/api/athlete/profile", json={"ftp": 200, "threshold_pace_run": 330})
        c.post("/api/plan/generate?use_llm=false")
        before = c.get("/api/plan").json()["workouts"]

        def future_bike_powers(workouts):
            from datetime import date, timedelta
            next_monday = (date.today() + timedelta(days=7 - date.today().weekday())).isoformat()
            out = []
            for w in workouts:
                if w["sport"] == "Bike" and (w["date"] or "") >= next_monday:
                    for s in w["steps"]:
                        t = s.get("target") or {}
                        if t.get("type") == "power" and t.get("high"):
                            out.append(t["high"])
            return out

        p_before = future_bike_powers(before)
        assert p_before, "expected future bike power targets in the plan"

        r = c.put("/api/athlete/profile", json={"ftp": 250}).json()
        assert r.get("plan_weeks_refreshed", 0) > 0

        after = c.get("/api/plan").json()["workouts"]
        p_after = future_bike_powers(after)
        # FTP +25% → power targets scale up.
        assert max(p_after) > max(p_before)

        # Saving a non-target field must NOT churn the plan.
        r2 = c.put("/api/athlete/profile", json={"weekly_hours_target": 8}).json()
        assert "plan_weeks_refreshed" not in r2 or r2["plan_weeks_refreshed"] == 0


def test_threshold_refresh_never_touches_current_week():
    from datetime import date, timedelta

    from starlette.testclient import TestClient

    from app.main import app

    with TestClient(app) as c:
        c.put("/api/athlete/profile", json={"ftp": 200})
        c.post("/api/plan/generate?use_llm=false")
        next_monday = (date.today() + timedelta(days=7 - date.today().weekday())).isoformat()
        before_ids = {w["id"] for w in c.get("/api/plan").json()["workouts"]
                      if (w["date"] or "") < next_monday}
        c.put("/api/athlete/profile", json={"ftp": 260})
        after_ids = {w["id"] for w in c.get("/api/plan").json()["workouts"]
                     if (w["date"] or "") < next_monday}
        assert before_ids == after_ids  # current-week rows untouched (ids stable)
