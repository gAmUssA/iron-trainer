"""Nutrition & fueling endpoints.

Per-workout and daily targets are pure math (nutrition.py). The race-day
timeline is deterministic by default and can be re-generated with the LLM,
which is always run back through the safety validator.
"""

from __future__ import annotations

from fastapi import APIRouter, HTTPException, Query

from .. import auth, dashboards, jobs, nutrition, repo
from ..logging_config import get_logger
from ..planning import llm

router = APIRouter(prefix="/api/nutrition", tags=["nutrition"])
log = get_logger("nutrition")


def _profile() -> dict:
    a = repo.get_athlete()
    return {
        "body_weight_kg": a.get("body_weight_kg"),
        "gel_carb_g": a.get("gel_carb_g"),
        "sweat_rate_l_h": a.get("sweat_rate_l_h"),
        "gi_tolerance": a.get("gi_tolerance"),
        "weekly_hours_target": a.get("weekly_hours_target"),
    }


def _readiness() -> dict:
    metrics_rows = repo.get_metrics()
    current_ctl = metrics_rows[-1]["ctl"] if metrics_rows else None
    race = repo.effective_race()
    cutoffs = {
        "swim": race["cutoff_swim_s"],
        "bike": race["cutoff_bike_s"],
        "finish": race["cutoff_finish_s"],
    }
    return dashboards.race_readiness(
        repo.list_activities(),
        repo.athlete_thresholds(),
        current_ctl=current_ctl,
        cutoffs=cutoffs,
        distance=race.get("distance"),
    )


@router.get("/workout/{workout_id}")
def workout_fueling(workout_id: int) -> dict:
    """Deterministic fueling targets for one planned workout."""
    wo = repo.get_workout(workout_id)
    if not wo:
        raise HTTPException(404, "Workout not found")
    return {"workout_id": workout_id, "fueling": nutrition.compute_workout_fueling(wo, _profile())}


@router.get("/daily")
def daily() -> dict:
    """Daily carbohydrate target based on current training volume."""
    profile = _profile()
    weight = profile.get("body_weight_kg")
    if not weight:
        return {"body_weight_kg": None, "daily_carb_g": None,
                "note": "Set body weight in Thresholds for daily carb targets."}
    weekly = float(profile.get("weekly_hours_target") or 0.0)
    return {
        "body_weight_kg": weight,
        "weekly_hours": weekly,
        "daily_carb_g": nutrition.daily_carb_target(float(weight), weekly),
        "pre_race": nutrition.pre_race_meal_target(float(weight)),
        "recovery_carb_g": nutrition.recovery_target(float(weight)),
    }


@router.get("/race-day")
def race_day() -> dict:
    """Deterministic race-day fueling timeline (no LLM)."""
    return nutrition.compute_race_day_plan(_profile(), repo.effective_race(), _readiness())


def _regenerate_race_day() -> dict:
    profile, race, readiness = _profile(), repo.effective_race(), _readiness()
    base = nutrition.compute_race_day_plan(profile, race, readiness)
    try:
        llm_out = llm.generate_race_day_nutrition(profile, race, readiness, base)
        plan = nutrition.apply_llm_timeline(base, llm_out)
        log.info("Race-day nutrition regenerated with LLM (%d items).", len(plan.get("items", [])))
        return plan
    except llm.LLMUnavailable as e:
        log.warning("LLM unavailable for race-day nutrition, using deterministic plan: %s", e)
        base["adjustments"] = ["LLM unavailable — showing the deterministic plan."] + base.get("adjustments", [])
        return base


@router.post("/race-day/regenerate")
def race_day_regenerate(run_async: bool = Query(False, alias="async")) -> dict:
    """Re-generate the race-day timeline with the LLM, falling back to the
    deterministic plan when the LLM is unavailable. Always safety-validated."""
    if run_async:
        aid = auth.current_athlete_id()
        return {"job": jobs.submit("nutrition_regen", _regenerate_race_day, athlete_id=aid)}
    return _regenerate_race_day()
