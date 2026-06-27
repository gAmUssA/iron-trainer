"""Fitness-test library endpoints: catalog, record/apply results, schedule the test
as a plan workout, and prefill inputs from a recent Strava activity."""

from __future__ import annotations

from datetime import date, datetime, timezone

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from .. import fitness_tests, repo

router = APIRouter(prefix="/api/tests", tags=["tests"])


class RecordRequest(BaseModel):
    test_slug: str
    date: str | None = None
    inputs: dict


class ScheduleRequest(BaseModel):
    date: str


def _today() -> str:
    return datetime.now(timezone.utc).date().isoformat()


@router.get("")
def list_tests() -> dict:
    """The protocol catalog, annotated with each athlete's last-tested date + whether
    a re-test is due (older than the re-test window)."""
    last = repo.last_tested_by_sport()
    today = date.fromisoformat(_today())
    out = []
    for proto in fitness_tests.catalog():
        last_date = last.get(proto["sport"])
        due = True
        if last_date:
            age = (today - date.fromisoformat(last_date)).days
            due = age >= fitness_tests.RETEST_DAYS
        out.append({**proto, "last_tested": last_date, "due": due})
    return {"tests": out, "retest_days": fitness_tests.RETEST_DAYS}


@router.post("/result")
def record_result(body: RecordRequest) -> dict:
    if fitness_tests.get(body.test_slug) is None:
        raise HTTPException(404, "Unknown test protocol")
    try:
        result = fitness_tests.compute(body.test_slug, body.inputs)
    except (KeyError, TypeError) as e:
        raise HTTPException(400, f"Missing or invalid inputs: {e}") from e
    return repo.save_test_result(body.test_slug, body.date or _today(), body.inputs, result)


@router.get("/results")
def list_results() -> dict:
    return {"results": repo.list_test_results()}


@router.post("/result/{result_id}/apply")
def apply_result(result_id: int) -> dict:
    applied = repo.apply_test_result(result_id)
    if applied is None:
        raise HTTPException(404, "Test result not found")
    return applied


@router.post("/{slug}/schedule")
def schedule_test(slug: str, body: ScheduleRequest) -> dict:
    if fitness_tests.get(slug) is None:
        raise HTTPException(404, "Unknown test protocol")
    try:
        return repo.add_test_workout_to_plan(slug, body.date)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


@router.get("/{slug}/prefill")
def prefill(slug: str, limit: int = 8) -> dict:
    """Recent activities of the protocol's sport with candidate inputs pre-mapped, so
    the UI can fill the form from a synced effort. Empty for swim (two-TT protocol)."""
    proto = fitness_tests.get(slug)
    if proto is None:
        raise HTTPException(404, "Unknown test protocol")
    sport = proto.get("prefill_sport")
    if not sport:
        return {"candidates": []}
    candidates = []
    # list_activities() is oldest-first; scan most-recent-first so prefill suggests
    # recent efforts (you just did the test), not stale ones.
    for a in reversed(repo.list_activities()):
        if a.get("sport") != sport:
            continue
        inputs: dict = {}
        if slug == "bike-ftp-20":
            p = a.get("weighted_power") or a.get("avg_power")
            if not p:
                continue
            inputs = {"avg_power_w": round(p)}
        elif slug == "run-lthr-30":
            if not (a.get("distance") and a.get("moving_time")):
                continue
            inputs = {
                "distance_m": round(a["distance"]),
                "time_s": round(a["moving_time"]),
                "avg_hr_last20": round(a["avg_hr"]) if a.get("avg_hr") else None,
            }
        if inputs:
            candidates.append({
                "activity_id": a.get("id"), "date": (a.get("start_date") or "")[:10],
                "name": a.get("name"), "inputs": inputs,
            })
        if len(candidates) >= limit:
            break
    return {"candidates": candidates}
