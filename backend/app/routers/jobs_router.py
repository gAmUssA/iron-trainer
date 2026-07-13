"""Background-job status endpoints."""

from __future__ import annotations

from fastapi import APIRouter, HTTPException

from .. import auth, repo

router = APIRouter(prefix="/api/jobs", tags=["jobs"])


@router.get("/summary")
def summary() -> dict:
    """Latest terminal job per kind + any currently active jobs — powers the
    'Strava last called / plan last generated' UI."""
    auth.current_athlete_id()
    return {"latest": repo.latest_jobs_by_kind(), "active": repo.active_jobs_by_kind()}


@router.get("/{job_id}")
def get_job(job_id: int) -> dict:
    job = repo.get_job(job_id)  # athlete-scoped: cross-tenant ids read as missing
    if job is None:
        raise HTTPException(404, "Job not found")
    return job
