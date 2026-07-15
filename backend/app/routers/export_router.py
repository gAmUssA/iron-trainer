"""Workout file export endpoints (.fit / .zwo / weekly & plan zip bundles).

Strangler seam: when EXPORT_PROXY_URL is set, BEARER-authenticated requests
for the endpoints backend-v2 implements are proxied to it over Railway's
private network. Session-cookie (web) traffic and the zip bundles stay
Python-served. Any proxy failure falls back to local serving — the flip can
never break a client that worked yesterday.
"""

from __future__ import annotations

import httpx
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import Response

from .. import repo
from ..config import get_settings
from ..export import service
from ..logging_config import get_logger

router = APIRouter(prefix="/api/export", tags=["export"])
log = get_logger("export")

_PROXY_TIMEOUT_S = 30.0
_HOP_HEADERS = {"content-length", "transfer-encoding", "connection"}


def _maybe_proxy(request: Request) -> Response | None:
    """Proxy this request to backend-v2 when the flip is on and the caller
    authenticated with a bearer (backend-v2 re-authenticates it). Returns
    None to serve locally: flip off, cookie auth, or backend-v2 unreachable."""
    proxy_base = get_settings().export_proxy_url.rstrip("/")
    if not proxy_base:
        return None
    authz = request.headers.get("authorization", "")
    if not authz.lower().startswith("bearer "):
        return None  # web session traffic stays local until Phase 7
    url = proxy_base + request.url.path
    try:
        upstream = httpx.get(url, headers={"Authorization": authz},
                             timeout=_PROXY_TIMEOUT_S)
    except httpx.HTTPError as e:
        log.warning("Export proxy unreachable (%s) — serving locally: %s", url, e)
        return None
    log.info("Export proxied to backend-v2: %s -> %d", request.url.path,
             upstream.status_code)
    headers = {k: v for k, v in upstream.headers.items()
               if k.lower() not in _HOP_HEADERS}
    return Response(content=upstream.content, status_code=upstream.status_code,
                    headers=headers)


@router.get("/workout/{workout_id}.fit")
def workout_fit(workout_id: int, request: Request) -> Response:
    if (proxied := _maybe_proxy(request)) is not None:
        return proxied
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
def workout_zwo(workout_id: int, request: Request) -> Response:
    if (proxied := _maybe_proxy(request)) is not None:
        return proxied
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
def workout_itw(workout_id: int, request: Request) -> Response:
    if (proxied := _maybe_proxy(request)) is not None:
        return proxied
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
def plan_itw(request: Request) -> Response:
    """The whole active plan as one .itw JSON doc — what the iOS app fetches over
    HTTP (with a bearer token). Workouts list is empty if there's no plan yet."""
    if (proxied := _maybe_proxy(request)) is not None:
        return proxied
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
