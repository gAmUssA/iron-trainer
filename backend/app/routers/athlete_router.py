"""Athlete profile: view, edit thresholds, re-infer from history."""

from __future__ import annotations

from datetime import date

from fastapi import APIRouter
from pydantic import BaseModel

from .. import analysis, repo

router = APIRouter(prefix="/api/athlete", tags=["athlete"])

_PUBLIC = (
    "strava_athlete_id",
    "name",
    "ftp",
    "threshold_hr",
    "max_hr",
    "threshold_pace_run",
    "css_swim",
    "weekly_hours_target",
    "updated_at",
)


class ProfileUpdate(BaseModel):
    ftp: float | None = None
    threshold_hr: int | None = None
    max_hr: int | None = None
    threshold_pace_run: float | None = None
    css_swim: float | None = None
    weekly_hours_target: float | None = None


@router.get("")
def get_profile() -> dict:
    a = repo.get_athlete()
    public = {k: a.get(k) for k in _PUBLIC}
    return {
        "connected": bool(a.get("strava_refresh_token")),
        "profile": public,
    }


@router.put("/profile")
def update_profile(update: ProfileUpdate) -> dict:
    repo.save_profile(update.model_dump(exclude_none=True))
    # Threshold changes alter TSS for every activity and thus the whole PMC.
    repo.recompute_tss()
    repo.rebuild_metrics()
    return get_profile()


@router.post("/infer")
def infer(apply: bool = False) -> dict:
    """Preview thresholds inferred from history; optionally persist them."""
    profile = analysis.infer_profile(repo.list_activities(), today=date.today())
    if apply:
        repo.save_profile(profile.as_dict())
        repo.recompute_tss()
        repo.rebuild_metrics()
    return {"inferred": profile.as_dict(), "applied": apply}
