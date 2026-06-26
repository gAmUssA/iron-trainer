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
