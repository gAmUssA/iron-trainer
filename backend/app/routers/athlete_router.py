"""Athlete profile: view, edit thresholds, re-infer from history."""

from __future__ import annotations

from datetime import date
from typing import Literal

from fastapi import APIRouter
from pydantic import BaseModel, Field

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
    "body_weight_kg",
    "gel_carb_g",
    "sweat_rate_l_h",
    "gi_tolerance",
    "updated_at",
)


class ProfileUpdate(BaseModel):
    # Bounds are generous sanity limits, not physiology: they exist so a typo
    # can't poison downstream math (negative weight → negative carb targets).
    ftp: float | None = Field(None, gt=0, lt=1000)
    threshold_hr: int | None = Field(None, gt=60, lt=250)
    max_hr: int | None = Field(None, gt=60, lt=250)
    threshold_pace_run: float | None = Field(None, gt=90, lt=1200)  # sec/km
    css_swim: float | None = Field(None, gt=40, lt=600)  # sec/100m
    weekly_hours_target: float | None = Field(None, gt=0, lt=60)
    body_weight_kg: float | None = Field(None, gt=25, lt=250)
    gel_carb_g: float | None = Field(None, gt=5, lt=120)
    sweat_rate_l_h: float | None = Field(None, gt=0, lt=5)
    gi_tolerance: Literal["low", "medium", "high"] | None = None


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
