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

from .. import dashboards, nutrition, readiness, reconcile as recon, repo
from ..logging_config import get_logger
from . import llm, template
from .template import monday_of
from .validator import validate_season, validate_week_workouts

log = get_logger("planning")


def _nutrition_profile() -> dict:
    a = repo.get_athlete()
    return {
        "body_weight_kg": a.get("body_weight_kg"),
        "gel_carb_g": a.get("gel_carb_g"),
        "sweat_rate_l_h": a.get("sweat_rate_l_h"),
    }


def _apply_fueling(workouts: list[dict], profile: dict) -> None:
    """Append a one-line fueling note to every workout that warrants fueling
    (>~45 min). Mutates the workout descriptions in place so it flows through to
    export files and the plan UI."""
    for wo in workouts:
        fueling = nutrition.compute_workout_fueling(wo, profile)
        wo["fueling"] = fueling
        note = nutrition.fueling_note(fueling)
        if note:
            base = (wo.get("description") or "").rstrip()
            if note not in base:
                wo["description"] = f"{base}\n{note}".strip()


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
    ready = readiness.compute(metrics, recovery=repo.recent_recovery())
    if ready.get("status") == "ok":
        summary["readiness_today"] = {
            "call": ready["call"],
            "acwr": ready["acwr"],
            "reason": (ready.get("reasons") or [None])[0],
        }
    # Compounding memory: how the athlete FELT in past weeks (1-5, higher is
    # better) next to the readiness call each time. Lets the planner see e.g.
    # three weeks of sliding sleep that raw load numbers would never show.
    past = [
        {"date": c["date"], "feel": c["inputs"],
         "readiness_call": (c.get("readiness") or {}).get("call")}
        for c in repo.recent_checkins()
        if c.get("inputs") or c.get("readiness")
    ]
    if past:
        summary["recent_checkins"] = past
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
    season["base_weekly_hours"] = weekly_hours  # recorded on the plan for staleness checks

    log.info("Generating plan for athlete %s: race=%r on %s, use_llm=%s",
             repo.get_athlete().get("id"), race_name, race_date, use_llm)
    llm_used = False
    if use_llm:
        try:
            season = llm.adjust_season(season, profile, _fitness_summary())
            season["race_name"] = race_name
            season["race_date"] = race_date.isoformat()
            season["base_weekly_hours"] = weekly_hours
            llm_used = True
        except llm.LLMUnavailable as e:
            log.warning("LLM unavailable, using deterministic template: %s", e)
            llm_used = False

    season, adjustments = validate_season(season)
    plan_id = repo.save_plan(season)

    # Expand every week with the template so the plan is complete & exportable.
    fuel_profile = _nutrition_profile()
    all_workouts: list[dict] = []
    for wk in season["weeks"]:
        workouts = template.expand_week(wk, profile)
        workouts, _ = validate_week_workouts(workouts)
        _apply_fueling(workouts, fuel_profile)
        all_workouts.extend(workouts)
    saved = repo.save_workouts(plan_id, all_workouts)
    log.info("Plan %d created: %s, %d weeks, %d workouts, %d safety adjustment(s).",
             plan_id, "AI-adapted" if llm_used else "template", len(season["weeks"]),
             saved, len(adjustments))

    return {
        "plan_id": plan_id,
        "llm_used": llm_used,
        "weeks": len(season["weeks"]),
        "workouts": saved,
        "adjustments": adjustments,
        "summary": season.get("summary"),
    }


def refresh_future_plan_targets(*, today: date | None = None) -> int:
    """Re-derive workout targets for every FUTURE week of the active plan from
    the athlete's CURRENT thresholds (and re-cost fueling), so the plan keeps
    up as fitness improves. Strictly future weeks only: past and current-week
    workouts — and their completed/matched history — are never touched. Week
    volumes/phases are preserved (that's the plan); only the prescriptions
    inside the workouts move with the new thresholds.

    Note: a week previously hand-replanned by the LLM is re-expanded from the
    deterministic template here — acceptable, since reconcile replans upcoming
    weeks anyway. Returns the number of weeks refreshed."""
    plan = repo.get_active_plan()
    if not plan:
        return 0
    today = today or date.today()
    profile = {
        k: repo.get_athlete().get(k)
        for k in ("ftp", "threshold_hr", "max_hr", "threshold_pace_run", "css_swim")
    }
    fuel_profile = _nutrition_profile()
    current_week_start = monday_of(today).isoformat()
    refreshed = 0
    for week in plan["weeks"]:
        if week["week_start"] <= current_week_start:
            continue  # never touch history or the in-flight week
        workouts = template.expand_week(week, profile)
        workouts, _ = validate_week_workouts(workouts)
        _apply_fueling(workouts, fuel_profile)
        week_end = (datetime.fromisoformat(week["week_start"]).date() + timedelta(days=6)).isoformat()
        repo.replace_week_workouts(plan["id"], week["week_start"], week_end, workouts)
        refreshed += 1
    if refreshed:
        log.info("Refreshed targets for %d future week(s) from current thresholds.", refreshed)
    return refreshed


FEEL_KEYS = ("energy", "sleep", "body", "stress")  # all 1-5, higher is better


def sanitize_feel(inputs: dict | None) -> dict | None:
    """Clamp subjective check-in inputs to their contract: known keys, ints
    1-5 (higher is better), plus an optional short free-text note."""
    if not isinstance(inputs, dict):
        return None
    out: dict = {}
    for k in FEEL_KEYS:
        v = inputs.get(k)
        if isinstance(v, (int, float)):
            out[k] = max(1, min(5, int(v)))
    note = inputs.get("note")
    if isinstance(note, str) and note.strip():
        out["note"] = note.strip()[:280]
    return out or None


def _feel_vs_data_line(feel: dict | None, ready: dict) -> str | None:
    """The reconciliation the Weekly Review pattern calls the most useful part
    of a review: where how you FEEL and what the DATA says disagree."""
    scores = [v for k, v in (feel or {}).items() if k in FEEL_KEYS]
    if not scores or ready.get("status") != "ok":
        return None
    avg = sum(scores) / len(scores)
    level = ready.get("level")
    if avg <= 2.5 and level == "green":
        return (
            f"Feel vs data: you rate the week {avg:.1f}/5 but your training load is steady — "
            "the fatigue is probably coming from sleep or life stress, not the plan. Worth a look."
        )
    if avg >= 3.5 and level in ("amber", "red"):
        return (
            f"Feel vs data: you feel good ({avg:.1f}/5) but the numbers disagree — "
            f"{(ready.get('reasons') or [''])[0]} Don't let a good day bait an overreach."
        )
    return f"Feel vs data: aligned ({avg:.1f}/5 vs a {level} load picture)."


def replan_week(*, week_start: str, use_llm: bool = True, feel: dict | None = None) -> dict:
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
            context = _fitness_summary()
            if feel:
                context["todays_feel"] = feel
            workouts = llm.generate_week_workouts(week, profile, context)
            llm_used = bool(workouts)
        except llm.LLMUnavailable:
            workouts = None
    if not workouts:
        workouts = template.expand_week(week, profile)

    workouts, notes = validate_week_workouts(workouts)
    _apply_fueling(workouts, _nutrition_profile())
    week_end = (datetime.fromisoformat(week_start).date() + timedelta(days=6)).isoformat()
    n = repo.replace_week_workouts(plan["id"], week_start, week_end, workouts)
    return {"week_start": week_start, "llm_used": llm_used, "workouts": n, "notes": notes}


def weekly_checkin(*, today: date | None = None, use_llm: bool = True,
                   inputs: dict | None = None) -> dict:
    """The one-tap adaptive loop: sync Strava (best-effort) → match actuals →
    re-plan next week from compliance + form → surface due fitness tests and the
    upcoming key sessions — with a human-readable story of what changed and why.
    Composes existing machinery; deliberately adds no new state."""
    from .. import fitness_tests, services  # lazy: services is a peer module

    today = today or date.today()
    feel = sanitize_feel(inputs)
    story: list[str] = []
    out: dict = {"inputs": feel}

    # 1. Sync — best-effort; a missing connection or Strava hiccup must not
    #    block the rest of the loop (local data still reconciles fine).
    try:
        sync = services.run_sync(full=False)
        out["synced"] = {"fetched": sync["fetched"], "total": sync["total_activities"]}
        story.append(
            f"Synced {sync['fetched']} new activit{'y' if sync['fetched'] == 1 else 'ies'} from Strava."
            if sync["fetched"] else "Strava sync: nothing new since last time."
        )
    except services.NotConnected:
        out["synced"] = None
        story.append("Strava not connected — using local data.")
    except Exception as e:  # noqa: BLE001 — network/API errors degrade, not abort
        log.warning("Check-in sync failed: %s", e)
        out["synced"] = None
        story.append("Strava sync failed — continuing with local data.")

    plan = repo.get_active_plan()
    if not plan:
        out["status"] = "no_plan"
        story.append("No active plan — generate one on the Training Plan tab first.")
        out["story"] = story
        return out

    # Snapshot next week's planned hours BEFORE the replan, for the delta story.
    next_monday = (monday_of(today) + timedelta(days=7)).isoformat()
    next_sunday = (monday_of(today) + timedelta(days=13)).isoformat()

    def _week_hours() -> float:
        wos = repo.get_workouts(plan["id"])
        secs = sum((w.get("duration_s") or 0) for w in wos
                   if next_monday <= (w.get("date") or "") <= next_sunday)
        return round(secs / 3600, 1)

    before_h = _week_hours()

    # 2 + 3. Match actuals + replan the upcoming week (the athlete's subjective
    # state rides along into the replan prompt).
    rec = reconcile(today=today, weeks_ahead=1, use_llm=use_llm, feel=feel)
    after_h = _week_hours()
    out["reconcile"] = {k: rec[k] for k in ("matched", "compliance", "weeks_replanned", "form_flag")}
    out["next_week"] = {"week_start": next_monday, "hours_before": before_h, "hours_after": after_h}

    comp = rec["compliance"]
    if comp.get("planned_sessions"):
        pct = round((comp.get("completion_rate") or 0) * 100)
        story.append(
            f"Last 3 weeks: {comp['completed_sessions']} of {comp['planned_sessions']} "
            f"sessions completed ({pct}%)."
        )
    flag = rec["form_flag"]
    tsb = (repo.get_metrics() or [{}])[-1].get("tsb")
    if flag != "unknown":
        story.append(f"Form: {flag}" + (f" (TSB {tsb:+.0f})." if tsb is not None else "."))
    ready = readiness.compute(repo.get_metrics(), today=today,
                              recovery=repo.recent_recovery())
    out["readiness"] = ready
    ready_line = readiness.story_line(ready)
    if ready_line:
        story.append(ready_line)
    feel_line = _feel_vs_data_line(feel, ready)
    if feel_line:
        story.append(feel_line)
    if rec["weeks_replanned"]:
        delta = after_h - before_h
        if abs(delta) >= 0.2:
            verb = "stepped up" if delta > 0 else "eased"
            story.append(
                f"Week of {next_monday}: replanned — {verb} from {before_h}h to {after_h}h."
            )
        else:
            story.append(f"Week of {next_monday}: replanned at {after_h}h (volume unchanged).")

    # 4. Fitness tests due (drives the thresholds that drive everything else).
    last = repo.last_tested_by_sport()
    due = []
    for t in fitness_tests.catalog():
        sport = t["sport"]
        last_date = last.get(sport)
        days_ago = None
        if last_date:
            days_ago = (today - date.fromisoformat(last_date[:10])).days
        if last_date is None or days_ago >= fitness_tests.RETEST_DAYS:
            due.append({"slug": t["slug"], "name": t["name"], "sport": sport,
                        "last_tested": last_date, "days_ago": days_ago})
    out["tests_due"] = due
    if due:
        names = ", ".join(d["name"] for d in due[:3])
        story.append(f"Fitness test{'s' if len(due) > 1 else ''} due: {names} (Tests tab).")

    # 5. Key sessions across the athlete's actionable horizon: the REMAINDER of
    #    the current week plus the just-replanned week. Deliberately not limited
    #    to the replanned week — a mid-week check-in should still surface this
    #    Saturday's long ride even though that week is never replanned.
    week_ahead = [w for w in repo.get_workouts(plan["id"])
                  if today.isoformat() <= (w.get("date") or "") <= next_sunday]
    key = sorted(week_ahead, key=lambda w: w.get("planned_tss") or 0, reverse=True)[:2]
    out["key_sessions"] = [
        {"date": w["date"], "sport": w["sport"], "title": w["title"],
         "duration_s": w.get("duration_s")} for w in key
    ]
    if key:
        bits = [f"{w['title']} ({(w.get('duration_s') or 0) // 60} min) on {w['date']}" for w in key]
        story.append("Key sessions ahead: " + "; ".join(bits) + ".")

    out["status"] = "ok"
    out["story"] = story
    # Persist — past check-ins are the memory the next one reads. Best-effort:
    # a storage hiccup must not fail a check-in that already replanned.
    try:
        repo.save_checkin(day=today.isoformat(), inputs=feel, story=story, readiness=ready)
    except Exception as e:  # noqa: BLE001
        log.warning("Check-in persist failed: %s", e)
    return out


def reconcile(*, today: date | None = None, weeks_ahead: int = 1, use_llm: bool = True,
              feel: dict | None = None) -> dict:
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
    replanned = [replan_week(week_start=w["week_start"], use_llm=use_llm, feel=feel)
                 for w in upcoming]

    return {
        "matched": matched,
        "compliance": recon.recent_compliance(plan["id"], today),
        "weeks_replanned": [w["week_start"] for w in upcoming],
        "replanned": replanned,
        "form_flag": _form_flag((repo.get_metrics() or [{}])[-1].get("tsb")),
    }
