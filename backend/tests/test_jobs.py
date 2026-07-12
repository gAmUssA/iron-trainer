"""Background job system: submission, polling, tenancy, dedup, restart sweep."""

import time

from starlette.testclient import TestClient

from app import auth, repo
from app.main import app


def _wait_terminal(c: TestClient, job_id: int, timeout_s: float = 30.0) -> dict:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        job = c.get(f"/api/jobs/{job_id}").json()
        if job["status"] in ("succeeded", "failed"):
            return job
        time.sleep(0.1)
    raise AssertionError("job did not finish in time")


def test_async_generate_plan_and_poll():
    with TestClient(app) as c:
        c.put("/api/athlete/profile", json={"ftp": 228, "threshold_hr": 160,
                                            "weekly_hours_target": 7})
        r = c.post("/api/plan/generate?use_llm=false&async=1").json()
        assert "job" in r and r["job"]["status"] in ("queued", "running")
        job = _wait_terminal(c, r["job"]["id"])
        assert job["status"] == "succeeded"
        # Result carries the same shape the sync endpoint returns.
        assert job["result"]["workouts"] > 0
        assert job["result"]["llm_used"] is False
        # The plan really exists.
        assert c.get("/api/plan").json()["plan"] is not None


def test_duplicate_submit_returns_existing_job(monkeypatch):
    """Sequential AND simultaneous duplicate submits must run the work once —
    the simultaneous case exercises the submit lock (check-then-create race)."""
    import concurrent.futures

    from app.planning import service as psvc

    started = {"n": 0}

    def slow_generate(**kwargs):
        started["n"] += 1
        time.sleep(0.8)
        return {"ok": True}

    monkeypatch.setattr(psvc, "generate_plan", slow_generate)
    with TestClient(app) as c:
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as ex:
            futs = [ex.submit(lambda: c.post("/api/plan/generate?async=1").json()["job"])
                    for _ in range(2)]
            a, b = [f.result() for f in futs]
        assert a["id"] == b["id"]
        assert a.get("already_running") or b.get("already_running")
        _wait_terminal(c, a["id"])
        # And the plain sequential case:
        third = c.post("/api/plan/generate?async=1").json()["job"]
        _wait_terminal(c, third["id"])
    assert started["n"] == 2  # two distinct jobs ran; the racing pair ran ONCE


def test_async_import_conflicts_instead_of_discarding_upload():
    """A second import while one runs must 409 — not silently poll the old job
    while the new archive is dropped on the floor."""
    import io
    import zipfile

    from app import auth as auth_mod

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("activities.csv", "Activity ID\n")
    with TestClient(app) as c:
        # Create the blocker AFTER startup — the lifespan sweep (correctly)
        # fails any queued/running rows that predate the boot.
        tok = auth_mod.set_current_athlete_id(1)
        try:
            blocker = repo.create_job("import")  # simulate an in-flight import
        finally:
            auth_mod.reset_current_athlete_id(tok)
        r = c.post("/api/strava/import?async=1",
                   files={"file": ("export.zip", buf.getvalue(), "application/zip")})
        assert r.status_code == 409
        assert "already running" in r.json()["detail"]

    tok = auth_mod.set_current_athlete_id(1)
    try:
        repo.set_job_status(blocker["id"], "failed", error="test cleanup")
    finally:
        auth_mod.reset_current_athlete_id(tok)


def test_sync_job_records_not_connected_failure():
    with TestClient(app) as c:
        r = c.post("/api/strava/sync?async=1").json()
        job = _wait_terminal(c, r["job"]["id"])
        assert job["status"] == "failed"
        assert "not connected" in job["error"].lower()


def test_job_is_athlete_scoped():
    with TestClient(app) as c:
        r = c.post("/api/plan/generate?use_llm=false&async=1").json()
        job_id = r["job"]["id"]
        _wait_terminal(c, job_id)
    # Another athlete must not see it.
    other = repo.find_or_create_athlete(9999, "Other")
    tok = auth.set_current_athlete_id(other)
    try:
        assert repo.get_job(job_id) is None
    finally:
        auth.reset_current_athlete_id(tok)


def test_fail_stale_jobs_sweep():
    from app import jobs as jobs_mod

    tok = auth.set_current_athlete_id(1)
    try:
        row = repo.create_job("sync")
        repo.set_job_status(row["id"], "running")
    finally:
        auth.reset_current_athlete_id(tok)
    jobs_mod.fail_stale_running()
    tok = auth.set_current_athlete_id(1)
    try:
        swept = repo.get_job(row["id"])
    finally:
        auth.reset_current_athlete_id(tok)
    assert swept["status"] == "failed"
    assert "restart" in swept["error"]


def test_jobs_summary_reports_latest_terminal():
    with TestClient(app) as c:
        c.put("/api/athlete/profile", json={"ftp": 228, "weekly_hours_target": 6})
        r = c.post("/api/plan/generate?use_llm=false&async=1").json()
        _wait_terminal(c, r["job"]["id"])
        summary = c.get("/api/jobs/summary").json()
        assert summary["latest"]["generate_plan"]["status"] == "succeeded"
        assert summary["latest"]["generate_plan"]["finished_at"]
        assert summary["active"] == {}
