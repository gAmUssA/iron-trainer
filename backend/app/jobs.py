"""In-process background jobs for operations that talk to Strava or Claude.

Deliberately dependency-free: this app runs as a single uvicorn worker on
Railway, so a `threading.Thread` per job with the status persisted in the DB
is the whole system — no broker, no beat, nothing to operate. The `job` row is
the source of truth; a deploy/restart kills in-flight threads and the startup
sweep (`fail_stale_running`) marks their rows failed so the UI never shows a
forever-spinner.

The blocking work (httpx to Strava, the Anthropic SDK) runs unchanged inside
the thread; DB access uses the existing per-call `get_session()` (the engine is
`check_same_thread=False` + WAL for SQLite), and the multi-tenant ContextVar is
set explicitly in the thread — request-scoped context does not cross threads.
"""

from __future__ import annotations

import json
import threading
import traceback
from typing import Callable

from . import auth, repo
from .logging_config import get_logger

log = get_logger("jobs")

KINDS = ("sync", "import", "dedup", "generate_plan", "checkin", "nutrition_regen")


def submit(kind: str, fn: Callable[[], dict], *, athlete_id: int) -> dict:
    """Run `fn` in a background thread, tracked as a job row.

    One active job per (athlete, kind): a second submit while one is queued or
    running returns the existing job with `already_running: True` instead of
    doing duplicate (and rate-limited) work.
    """
    existing = repo.active_job(kind)
    if existing:
        return {**existing, "already_running": True}

    job = repo.create_job(kind)
    thread = threading.Thread(
        target=_run, args=(job["id"], athlete_id, fn),
        name=f"job-{kind}-{job['id']}", daemon=True,
    )
    thread.start()
    return job


def _run(job_id: int, athlete_id: int, fn: Callable[[], dict]) -> None:
    # ContextVars are per-thread here: set the tenant BEFORE any repo call.
    auth.set_current_athlete_id(athlete_id)
    repo.set_job_status(job_id, "running")
    try:
        result = fn()
        repo.set_job_status(job_id, "succeeded",
                            result_json=json.dumps(result, default=str))
        log.info("Job %s finished: succeeded.", job_id)
    except Exception as e:  # noqa: BLE001 — the row IS the error report
        log.error("Job %s failed: %s\n%s", job_id, e, traceback.format_exc())
        repo.set_job_status(job_id, "failed", error=str(e))


def fail_stale_running() -> None:
    """Startup hygiene: threads died with the previous process; their rows
    must not stay 'running' forever."""
    n = repo.fail_stale_jobs()
    if n:
        log.warning("Marked %d stale job(s) as failed (interrupted by restart).", n)
