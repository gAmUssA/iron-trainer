from datetime import date

from app import reconcile, repo
from app.planning import service


def _raw(aid, sport_type, start, moving, **kw):
    base = {
        "id": aid, "type": sport_type, "sport_type": sport_type,
        "start_date_local": start, "moving_time": moving, "elapsed_time": moving + 60,
        "distance": kw.get("distance", 10000),
    }
    base.update(kw)
    return base


def _setup_plan_and_actuals():
    # Season starts Monday 2026-06-01; "today" is mid-week-2.
    repo.save_profile({"ftp": 230, "threshold_pace_run": 300, "css_swim": 100})
    service.generate_plan(use_llm=False, today=date(2026, 6, 1))
    # Actuals matching three of week-1's sessions (swim 06-02, long bike 06-06, long run 06-07).
    repo.upsert_activities([
        _raw(1001, "Swim", "2026-06-02T08:00:00Z", 1800, distance=1800),
        _raw(1002, "Ride", "2026-06-06T08:00:00Z", 5400, distance=45000,
             average_watts=190, weighted_average_watts=205, device_watts=True),
        _raw(1003, "Run", "2026-06-07T08:00:00Z", 2400, distance=8000, average_heartrate=150),
    ])
    repo.recompute_tss()
    repo.rebuild_metrics(today=date(2026, 6, 10))
    return repo.get_active_plan()


def test_match_workouts_marks_completed_and_skipped():
    plan = _setup_plan_and_actuals()
    today = date(2026, 6, 10)
    stats = reconcile.match_workouts(plan["id"], today)

    assert stats["completed"] == 3       # the three sessions with matching activities
    assert stats["skipped"] >= 3         # other past sessions with no activity
    assert stats["upcoming"] > 0         # future weeks remain planned

    workouts = repo.get_workouts(plan["id"])
    completed = [w for w in workouts if w["status"] == "completed"]
    assert {w["matched_activity_id"] for w in completed} == {1001, 1002, 1003}
    # Future workouts are untouched.
    future = [w for w in workouts if w["date"] > today.isoformat()]
    assert future and all(w["status"] == "planned" for w in future)


def test_one_activity_matches_at_most_one_workout():
    plan = _setup_plan_and_actuals()
    reconcile.match_workouts(plan["id"], date(2026, 6, 10))
    workouts = repo.get_workouts(plan["id"])
    matched_ids = [w["matched_activity_id"] for w in workouts if w["matched_activity_id"]]
    assert len(matched_ids) == len(set(matched_ids))  # no double-use


def test_compliance_summaries():
    plan = _setup_plan_and_actuals()
    today = date(2026, 6, 10)
    reconcile.match_workouts(plan["id"], today)

    recent = reconcile.recent_compliance(plan["id"], today)
    assert recent["completed_sessions"] == 3
    assert 0 <= recent["completion_rate"] <= 1
    assert recent["actual_tss"] > 0

    weeks = reconcile.compliance_by_week(plan["id"])
    wk1 = next(w for w in weeks if w["week_start"] == "2026-06-01")
    assert wk1["completed"] == 3
    assert wk1["planned_tss"] > 0 and wk1["actual_tss"] > 0


def test_reconcile_replans_next_week_only():
    _setup_plan_and_actuals()
    result = service.reconcile(today=date(2026, 6, 10), weeks_ahead=1, use_llm=False)

    assert result["matched"]["completed"] == 3
    # The in-progress week (starting 2026-06-08) is NOT touched; next week is.
    assert result["weeks_replanned"] == ["2026-06-15"]
    assert result["replanned"][0]["workouts"] > 0
    assert result["form_flag"] in {"unknown", "fatigued", "normal", "fresh"}
