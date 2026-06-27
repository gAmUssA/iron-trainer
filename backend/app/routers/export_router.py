"""Workout file export endpoints (.fit / .zwo / weekly & plan zip bundles)."""

from __future__ import annotations

from fastapi import APIRouter, HTTPException
from fastapi.responses import Response

from .. import repo
from ..export import service

router = APIRouter(prefix="/api/export", tags=["export"])


@router.get("/workout/{workout_id}.fit")
def workout_fit(workout_id: int) -> Response:
    w = repo.get_workout(workout_id)
    if not w:
        raise HTTPException(404, "Workout not found")
    name, data = service.workout_fit(w)
    return Response(
        content=data,
        media_type="application/octet-stream",
        headers={"Content-Disposition": f'attachment; filename="{name}"'},
    )


@router.get("/workout/{workout_id}.zwo")
def workout_zwo(workout_id: int) -> Response:
    w = repo.get_workout(workout_id)
    if not w:
        raise HTTPException(404, "Workout not found")
    result = service.workout_zwo(w)
    if not result:
        raise HTTPException(400, "No .zwo for this workout (bike power only)")
    name, content = result
    return Response(
        content=content,
        media_type="application/xml",
        headers={"Content-Disposition": f'attachment; filename="{name}"'},
    )


@router.get("/workout/{workout_id}.itw")
def workout_itw(workout_id: int) -> Response:
    w = repo.get_workout(workout_id)
    if not w:
        raise HTTPException(404, "Workout not found")
    name, content = service.workout_itw(w)
    return Response(
        content=content,
        media_type="application/json",
        headers={"Content-Disposition": f'attachment; filename="{name}"'},
    )


@router.get("/plan.itw")
def plan_itw() -> Response:
    """The whole active plan as one .itw JSON doc — what the iOS app fetches over
    HTTP (with a bearer token). Workouts list is empty if there's no plan yet."""
    return Response(
        content=service.plan_itw(),
        media_type="application/json",
        headers={"Content-Disposition": 'attachment; filename="iron-trainer-plan.itw"'},
    )


@router.get("/week/{week_start}.zip")
def week_zip(week_start: str) -> Response:
    workouts = service.week_workouts(week_start)
    if not workouts:
        raise HTTPException(404, "No workouts in that week")
    data = service.bundle_zip(workouts)
    return _zip(data, f"iron-trainer-week-{week_start}.zip")


@router.get("/plan.zip")
def plan_zip() -> Response:
    workouts = repo.get_workouts()
    if not workouts:
        raise HTTPException(404, "No active plan to export")
    data = service.bundle_zip(workouts)
    return _zip(data, "iron-trainer-plan.zip")


def _zip(data: bytes, name: str) -> Response:
    return Response(
        content=data,
        media_type="application/zip",
        headers={"Content-Disposition": f'attachment; filename="{name}"'},
    )
