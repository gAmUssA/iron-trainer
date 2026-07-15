"""Training plan generation & retrieval endpoints."""

from __future__ import annotations

from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel

from .. import jobs, reconcile as reconcile_mod
from .. import repo
from ..planning import service

router = APIRouter(prefix="/api/plan", tags=["plan"])


class CheckinBody(BaseModel):
    """Subjective check-in inputs; keys sanitized server-side (sanitize_feel)."""

    inputs: dict | None = None


@router.post("/generate")
def generate(
    use_llm: bool = Query(True, description="Adapt the season with Claude"),
    run_async: bool = Query(False, alias="async"),
) -> dict:
    if run_async:
        return {"job": jobs.submit("generate_plan",
                                   lambda: service.generate_plan(use_llm=use_llm))}
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
def checkin(
    body: CheckinBody | None = None,
    use_llm: bool = Query(True),
    run_async: bool = Query(False, alias="async"),
) -> dict:
    """One-tap weekly check-in: sync → reconcile → replan next week → test-due
    nudges, with a narrative `story` of what changed and why. Synchronous by
    default for API simplicity; both web and iOS clients pass ?async=1 and
    poll the job home.

    Optional body carries the athlete's subjective inputs (energy/sleep/body/
    stress 1-5, higher is better, plus a note) for feel-vs-data reconciliation."""
    inputs = body.inputs if body else None
    if run_async:
        return {"job": jobs.submit("checkin",
                                   lambda: service.weekly_checkin(use_llm=use_llm,
                                                                  inputs=inputs))}
    return service.weekly_checkin(use_llm=use_llm, inputs=inputs)


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
