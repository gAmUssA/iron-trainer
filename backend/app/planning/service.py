"""Orchestrate plan generation: template prior -> LLM adaptation -> validator.

Strategy (cost-aware, still LLM-adaptive):
  * Season skeleton is adapted by the LLM in a single call (falls back to the
    deterministic template when the LLM is unavailable), then hard-validated.
  * All weeks are expanded with the template so the plan is immediately complete
    and exportable.
  * `replan_week` regenerates a single week's detail with the LLM on demand —
    that's where week-to-week adaptation to recent training happens.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta

from .. import dashboards, reconcile as recon, repo
from . import llm, template
from .template import monday_of
from .validator import validate_season, validate_week_workouts


def _form_flag(tsb: float | None) -> str:
    if tsb is None:
        return "unknown"
    if tsb < -25:
        return "fatigued"  # dig out: ease off / recover
    if tsb > 10:
        return "fresh"  # room to push
    return "normal"


def _fitness_summary() -> dict:
    metrics = repo.get_metrics()
    last = metrics[-1] if metrics else {}
    weekly = dashboards.weekly_volume(repo.list_activities(), weeks=4)
    summary = {
        "ctl": last.get("ctl"),
        "atl": last.get("atl"),
        "tsb": last.get("tsb"),
        "form_flag": _form_flag(last.get("tsb")),
        "recent_weeks": weekly,
    }
    plan = repo.get_active_plan()
    if plan:
        summary["recent_compliance"] = recon.recent_compliance(plan["id"])
    return summary


def generate_plan(*, use_llm: bool = True, today: date | None = None) -> dict:
    today = today or date.today()
    race = repo.effective_race()
    race_name = race["name"]
    race_date = date.fromisoformat(race["date"])
    profile = {
        k: repo.get_athlete().get(k)
        for k in ("ftp", "threshold_hr", "max_hr", "threshold_pace_run", "css_swim", "weekly_hours_target")
    }
    weekly_hours = profile.get("weekly_hours_target") or 6.0

    season = template.build_season(start=today, race_date=race_date, weekly_hours=weekly_hours)
    season["race_name"] = race_name

    llm_used = False
    if use_llm:
        try:
            season = llm.adjust_season(season, profile, _fitness_summary())
            season["race_name"] = race_name
            season["race_date"] = race_date.isoformat()
            llm_used = True
        except llm.LLMUnavailable:
            llm_used = False

    season, adjustments = validate_season(season)
    plan_id = repo.save_plan(season)

    # Expand every week with the template so the plan is complete & exportable.
    all_workouts: list[dict] = []
    for wk in season["weeks"]:
        workouts = template.expand_week(wk, profile)
        workouts, _ = validate_week_workouts(workouts)
        all_workouts.extend(workouts)
    saved = repo.save_workouts(plan_id, all_workouts)

    return {
        "plan_id": plan_id,
        "llm_used": llm_used,
        "weeks": len(season["weeks"]),
        "workouts": saved,
        "adjustments": adjustments,
        "summary": season.get("summary"),
    }


def replan_week(*, week_start: str, use_llm: bool = True) -> dict:
    plan = repo.get_active_plan()
    if not plan:
        raise ValueError("No active plan. Generate a plan first.")
    profile = {
        k: repo.get_athlete().get(k)
        for k in ("ftp", "threshold_hr", "max_hr", "threshold_pace_run", "css_swim")
    }
    week = next((w for w in plan["weeks"] if w["week_start"] == week_start), None)
    if not week:
        raise ValueError(f"Week {week_start} not in active plan.")

    llm_used = False
    workouts: list[dict] | None = None
    if use_llm:
        try:
            workouts = llm.generate_week_workouts(week, profile, _fitness_summary())
            llm_used = bool(workouts)
        except llm.LLMUnavailable:
            workouts = None
    if not workouts:
        workouts = template.expand_week(week, profile)

    workouts, notes = validate_week_workouts(workouts)
    week_end = (datetime.fromisoformat(week_start).date() + timedelta(days=6)).isoformat()
    n = repo.replace_week_workouts(plan["id"], week_start, week_end, workouts)
    return {"week_start": week_start, "llm_used": llm_used, "workouts": n, "notes": notes}


def reconcile(*, today: date | None = None, weeks_ahead: int = 1, use_llm: bool = True) -> dict:
    """Fold actual training back into the plan, then re-plan upcoming week(s).

    1. Match completed activities to planned workouts (status + compliance).
    2. Re-plan the next `weeks_ahead` week(s) using current form + compliance.

    The in-progress week is intentionally left untouched so already-completed
    sessions aren't overwritten; only future weeks are regenerated.
    """
    today = today or date.today()
    plan = repo.get_active_plan()
    if not plan:
        raise ValueError("No active plan. Generate a plan first.")

    matched = recon.match_workouts(plan["id"], today)

    next_monday = (monday_of(today) + timedelta(days=7)).isoformat()
    upcoming = [w for w in plan["weeks"] if w["week_start"] >= next_monday][:weeks_ahead]
    replanned = [replan_week(week_start=w["week_start"], use_llm=use_llm) for w in upcoming]

    return {
        "matched": matched,
        "compliance": recon.recent_compliance(plan["id"], today),
        "weeks_replanned": [w["week_start"] for w in upcoming],
        "replanned": replanned,
        "form_flag": _form_flag((repo.get_metrics() or [{}])[-1].get("tsb")),
    }
