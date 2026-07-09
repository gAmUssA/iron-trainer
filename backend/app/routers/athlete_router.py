"""Athlete profile: view, edit thresholds, re-infer from history."""

from __future__ import annotations

from datetime import date
from typing import Literal

from fastapi import APIRouter
from pydantic import BaseModel, Field

from .. import analysis, repo
from ..logging_config import get_logger
from ..planning import service as planning_service

router = APIRouter(prefix="/api/athlete", tags=["athlete"])
log = get_logger("athlete")

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


# Fields that drive workout prescriptions — changing any of these means future
# planned workouts should be re-derived so the plan tracks improving fitness.
_TARGET_FIELDS = ("ftp", "threshold_hr", "max_hr", "threshold_pace_run", "css_swim",
                  "body_weight_kg", "gel_carb_g", "sweat_rate_l_h")


@router.put("/profile")
def update_profile(update: ProfileUpdate) -> dict:
    # exclude_unset (not exclude_none): a field the client explicitly sent as
    # null clears the stored value; fields it didn't send stay untouched.
    changes = update.model_dump(exclude_unset=True)
    before = repo.get_athlete()
    repo.save_profile(changes)
    # Threshold changes alter TSS for every activity and thus the whole PMC.
    repo.recompute_tss()
    repo.rebuild_metrics()
    out = get_profile()
    # New thresholds → refresh FUTURE workout targets (never past/current week),
    # so the plan keeps up with improving fitness without a full regenerate.
    # Best-effort: the profile save above is already committed, so a refresh
    # failure must not turn a successful save into a 500.
    if any(k in changes and changes[k] != before.get(k) for k in _TARGET_FIELDS):
        try:
            out["plan_weeks_refreshed"] = planning_service.refresh_future_plan_targets()
        except Exception:  # noqa: BLE001 — log and degrade to "nothing refreshed"
            log.exception("Future-target refresh failed after profile update.")
            out["plan_weeks_refreshed"] = 0
    return out


@router.post("/infer")
def infer(apply: bool = False) -> dict:
    """Preview thresholds inferred from history; optionally persist them."""
    profile = analysis.infer_profile(repo.list_activities(), today=date.today())
    if apply:
        # Inference must never CLEAR a manually-set value it couldn't compute.
        repo.save_profile({k: v for k, v in profile.as_dict().items() if v is not None})
        repo.recompute_tss()
        repo.rebuild_metrics()
    return {"inferred": profile.as_dict(), "applied": apply}
