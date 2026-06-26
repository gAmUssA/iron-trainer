"""Reconcile the plan with reality: match completed Strava activities to planned
workouts and measure planned-vs-actual compliance.

This is the read side of the reconcile loop — it never changes future workouts;
it just records what actually happened (status + which activity fulfilled each
planned session) so the re-planning step and the dashboards have ground truth.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta

from . import repo

# A planned sport is satisfied by these actual activity sports.
_MATCHES: dict[str, set[str]] = {
    "Swim": {"Swim"},
    "Bike": {"Bike"},
    "Run": {"Run"},
    "Brick": {"Bike", "Run"},
}
# Sports we expect to see on Strava; others (e.g. Strength) aren't marked skipped.
_TRACKED = set(_MATCHES)


def _day(s: str | None) -> date | None:
    if not s:
        return None
    try:
        return datetime.fromisoformat(str(s).replace("Z", "+00:00")).date()
    except ValueError:
        return None


def match_workouts(plan_id: int, today: date | None = None) -> dict:
    """Set each past planned workout's status to completed/skipped and link the
    activity that fulfilled it. Future workouts stay 'planned'. One activity can
    only fulfil one planned workout."""
    today = today or date.today()
    workouts = repo.get_workouts(plan_id)
    activities = repo.list_activities()  # duplicates already excluded

    # Index activities by sport for quick day-window lookup.
    used: set[int] = set()
    acts_by_sport: dict[str, list[dict]] = {}
    for a in activities:
        acts_by_sport.setdefault(a.get("sport"), []).append(a)

    completed = skipped = planned = 0
    for w in sorted(workouts, key=lambda x: x.get("date") or ""):
        wdate = _day(w.get("date"))
        if not wdate:
            continue
        if wdate > today:
            if w.get("status") != "planned":
                repo.set_workout_status(w["id"], "planned", None)
            planned += 1
            continue

        match = _best_match(w, wdate, acts_by_sport, used)
        if match:
            used.add(match["id"])
            repo.set_workout_status(w["id"], "completed", match["id"])
            completed += 1
        elif w.get("sport") in _TRACKED:
            repo.set_workout_status(w["id"], "skipped", None)
            skipped += 1
        # untracked sports (e.g. Strength) keep their current status

    return {"completed": completed, "skipped": skipped, "upcoming": planned}


def _best_match(
    workout: dict, wdate: date, acts_by_sport: dict[str, list[dict]], used: set[int]
) -> dict | None:
    wanted = _MATCHES.get(workout.get("sport"))
    if not wanted:
        return None
    candidates = []
    for sport in wanted:
        for a in acts_by_sport.get(sport, []):
            if a["id"] in used:
                continue
            adate = _day(a.get("start_date"))
            if adate and abs((adate - wdate).days) <= 1:
                candidates.append((abs((adate - wdate).days), -(a.get("tss") or 0), a))
    if not candidates:
        return None
    candidates.sort(key=lambda t: (t[0], t[1]))  # nearest day, then biggest TSS
    return candidates[0][2]


# ── Compliance ────────────────────────────────────────────────────────────────


def _week_start(d: date) -> date:
    return d - timedelta(days=d.weekday())


def compliance_by_week(plan_id: int) -> list[dict]:
    """Planned vs actual per ISO week, with completed/skipped counts."""
    workouts = repo.get_workouts(plan_id)
    act_tss = {a["id"]: (a.get("tss") or 0.0) for a in repo.list_activities()}
    act_secs = {a["id"]: (a.get("moving_time") or 0) for a in repo.list_activities()}

    weeks: dict[date, dict] = {}
    for w in workouts:
        d = _day(w.get("date"))
        if not d:
            continue
        ws = _week_start(d)
        b = weeks.setdefault(
            ws,
            {"planned_tss": 0.0, "actual_tss": 0.0, "planned_hours": 0.0, "actual_hours": 0.0,
             "completed": 0, "skipped": 0, "planned": 0},
        )
        b["planned_tss"] += w.get("planned_tss") or 0.0
        b["planned_hours"] += (w.get("duration_s") or 0) / 3600.0
        status = w.get("status")
        if status == "completed":
            b["completed"] += 1
            mid = w.get("matched_activity_id")
            b["actual_tss"] += act_tss.get(mid, 0.0)
            b["actual_hours"] += act_secs.get(mid, 0) / 3600.0
        elif status == "skipped":
            b["skipped"] += 1
        else:
            b["planned"] += 1

    out = []
    for ws in sorted(weeks):
        b = weeks[ws]
        out.append({
            "week_start": ws.isoformat(),
            **{k: round(v, 1) if isinstance(v, float) else v for k, v in b.items()},
        })
    return out


def recent_compliance(plan_id: int, today: date | None = None, *, days: int = 21) -> dict:
    """Compact planned-vs-actual summary over the recent window, for LLM context."""
    today = today or date.today()
    cutoff = today - timedelta(days=days)
    workouts = repo.get_workouts(plan_id)
    act_tss = {a["id"]: (a.get("tss") or 0.0) for a in repo.list_activities()}

    planned_n = completed_n = skipped_n = 0
    planned_tss = actual_tss = 0.0
    for w in workouts:
        d = _day(w.get("date"))
        if not d or d < cutoff or d > today:
            continue
        planned_n += 1
        planned_tss += w.get("planned_tss") or 0.0
        if w.get("status") == "completed":
            completed_n += 1
            actual_tss += act_tss.get(w.get("matched_activity_id"), 0.0)
        elif w.get("status") == "skipped":
            skipped_n += 1

    return {
        "window_days": days,
        "planned_sessions": planned_n,
        "completed_sessions": completed_n,
        "skipped_sessions": skipped_n,
        "completion_rate": round(completed_n / planned_n, 2) if planned_n else None,
        "planned_tss": round(planned_tss),
        "actual_tss": round(actual_tss),
        "load_ratio": round(actual_tss / planned_tss, 2) if planned_tss else None,
    }
