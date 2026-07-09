"""Training plan generation & retrieval endpoints."""

from __future__ import annotations

from fastapi import APIRouter, HTTPException, Query

from .. import reconcile as reconcile_mod
from .. import repo
from ..planning import service

router = APIRouter(prefix="/api/plan", tags=["plan"])


@router.post("/generate")
def generate(use_llm: bool = Query(True, description="Adapt the season with Claude")) -> dict:
    return service.generate_plan(use_llm=use_llm)


@router.get("")
def get_plan() -> dict:
    plan = repo.get_active_plan()
    if not plan:
        return {"plan": None, "workouts": []}
    return {"plan": plan, "workouts": repo.get_workouts(plan["id"])}


@router.post("/replan-week")
def replan_week(week_start: str, use_llm: bool = Query(True)) -> dict:
    try:
        return service.replan_week(week_start=week_start, use_llm=use_llm)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


@router.post("/checkin")
def checkin(use_llm: bool = Query(True)) -> dict:
    """One-tap weekly check-in: sync → reconcile → replan next week → test-due
    nudges, with a narrative `story` of what changed and why."""
    return service.weekly_checkin(use_llm=use_llm)


@router.post("/reconcile")
def reconcile(
    weeks_ahead: int = Query(1, ge=1, le=4, description="How many upcoming weeks to re-plan"),
    use_llm: bool = Query(True),
) -> dict:
    """Match completed workouts, then re-plan the upcoming week(s) from latest data."""
    try:
        return service.reconcile(weeks_ahead=weeks_ahead, use_llm=use_llm)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


@router.get("/compliance")
def compliance() -> dict:
    """Planned-vs-actual per week + a recent-window summary."""
    plan = repo.get_active_plan()
    if not plan:
        return {"weeks": [], "recent": None}
    return {
        "weeks": reconcile_mod.compliance_by_week(plan["id"]),
        "recent": reconcile_mod.recent_compliance(plan["id"]),
    }
