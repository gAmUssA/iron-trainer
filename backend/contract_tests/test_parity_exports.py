"""Cross-implementation parity: the SAME export requests against FastAPI and
backend-v2 must agree. Skipped unless V2_BASE_URL is set (the parity runner
provides it plus a real bearer paired through the FastAPI API)."""

import json
import os
import subprocess
from datetime import date, timedelta

import httpx
import pytest

V2_BASE_URL = os.environ.get("V2_BASE_URL")
pytestmark = pytest.mark.skipif(not V2_BASE_URL, reason="V2_BASE_URL not set (parity run only)")


@pytest.fixture(scope="session")
def bearer(api, seeded) -> str:
    code = api.post("/api/device/pairing-code").json()["code"]
    out = httpx.post(f"{api.base_url}/api/device/claim",
                     json={"code": code, "device_name": "parity"}, timeout=30)
    out.raise_for_status()
    return out.json()["token"]


def _psql(sql: str) -> str:
    """Run SQL in the parity Postgres container (PARITY_PG_ID) via docker exec.
    The parity harness owns that container; -v ON_ERROR_STOP=1 fails loudly."""
    pg = os.environ["PARITY_PG_ID"]
    out = subprocess.run(
        ["docker", "exec", "-i", pg, "psql", "-U", "postgres", "-d", "iron",
         "-tAq", "-v", "ON_ERROR_STOP=1", "-c", sql],
        capture_output=True, text=True,
    )
    if out.returncode != 0:
        raise RuntimeError(f"psql failed: {out.stderr.strip()}")
    return out.stdout.strip()


@pytest.fixture(scope="session")
def seeded_metrics(bearer) -> None:
    """Seed a steady 42-day load + suppressed-HRV recovery straight into the
    shared Postgres, for the bearer's athlete, AFTER `seeded` has run.

    Why direct DB and not the public API: metrics_daily is derived from Strava
    activities (no public write path), and — critically — the `seeded` fixture's
    profile PUT calls rebuild_metrics(), which DELETEs the athlete's rows. Any
    seed done before that wipe vanishes. This fixture depends on `bearer` (hence
    `seeded`), so it runs after the last rebuild. Requires PARITY_PG_ID, set by
    run_parity.sh; skips cleanly outside the parity runner."""
    if not os.environ.get("PARITY_PG_ID"):
        pytest.skip("PARITY_PG_ID not set (parity runner only)")
    # The bearer's athlete is the most-recently paired device_token row.
    aid = _psql("SELECT athlete_id FROM device_token ORDER BY id DESC LIMIT 1")
    assert aid.isdigit(), f"could not resolve bearer athlete id (got {aid!r})"

    # Dates are computed with the TEST's date.today() (host-local tz), NOT
    # Postgres CURRENT_DATE. The FastAPI/Quarkus apps read "today" as the host's
    # local date; the Postgres container runs UTC. When those differ (evening in
    # a UTC-behind tz), a CURRENT_DATE-based "today" recovery row lands in the
    # app's future and _recovery_flags drops it — the suppressed-HRV signal
    # vanishes. Seeding host-local dates keeps the seed and the app aligned.
    today = date.today()
    day = lambda g: (today - timedelta(days=g)).isoformat()
    metrics_vals = ",".join(f"({aid}, '{day(g)}', 50, 50, 50, 0)" for g in range(1, 43))
    base_vals = ",".join(f"({aid}, '{day(g)}', 7.5, 60, 46)" for g in range(1, 10))
    _psql(f"""
        INSERT INTO metrics_daily (athlete_id, date, tss, ctl, atl, tsb)
        VALUES {metrics_vals}
        ON CONFLICT (athlete_id, date) DO NOTHING;
        INSERT INTO daily_recovery (athlete_id, date, sleep_h, hrv_ms, rhr_bpm)
        VALUES {base_vals}
        ON CONFLICT (athlete_id, date) DO NOTHING;
        INSERT INTO daily_recovery (athlete_id, date, sleep_h, hrv_ms, rhr_bpm)
        VALUES ({aid}, '{today.isoformat()}', 7.5, 40, 46)
        ON CONFLICT (athlete_id, date) DO NOTHING;
    """)


@pytest.fixture(scope="session")
def v2(bearer) -> httpx.Client:
    c = httpx.Client(base_url=V2_BASE_URL, timeout=60,
                     headers={"Authorization": f"Bearer {bearer}"})
    yield c
    c.close()


@pytest.fixture(scope="session")
def v1(bearer, api) -> httpx.Client:
    c = httpx.Client(base_url=str(api.base_url), timeout=60,
                     headers={"Authorization": f"Bearer {bearer}"})
    yield c
    c.close()


def _bike_id(v1: httpx.Client) -> int:
    workouts = v1.get("/api/plan").json()["workouts"]
    return next(w["id"] for w in workouts if w["sport"] == "Bike")


def test_itw_parity(v1, v2):
    wid = _bike_id(v1)
    a = v1.get(f"/api/export/workout/{wid}.itw")
    b = v2.get(f"/api/export/workout/{wid}.itw")
    assert a.status_code == b.status_code == 200
    # Whitespace/key-order free comparison: parsed JSON must be IDENTICAL.
    assert json.loads(a.text) == json.loads(b.text)


def test_plan_itw_parity(v1, v2):
    a = json.loads(v1.get("/api/export/plan.itw").text)
    b = json.loads(v2.get("/api/export/plan.itw").text)
    assert a["schema_version"] == b["schema_version"] == 1
    assert a["plan"] == b["plan"]
    assert len(a["workouts"]) == len(b["workouts"])
    assert a["workouts"] == b["workouts"]


def test_zwo_parity(v1, v2):
    wid = _bike_id(v1)
    a = v1.get(f"/api/export/workout/{wid}.zwo")
    b = v2.get(f"/api/export/workout/{wid}.zwo")
    assert a.status_code == b.status_code
    if a.status_code == 200:
        # Segment-level equality, whitespace-insensitive.
        norm = lambda t: [l.strip() for l in t.strip().splitlines() if l.strip()]
        assert norm(a.text) == norm(b.text)


def test_fit_both_valid(v1, v2):
    """FIT bytes intentionally DIFFER (v2 encodes spec-correct ms durations —
    iron-trainer-sqib); assert both are valid FIT with the same step count."""
    wid = _bike_id(v1)
    a = v1.get(f"/api/export/workout/{wid}.fit").content
    b = v2.get(f"/api/export/workout/{wid}.fit").content
    assert a[8:12] == b[8:12] == b".FIT"
    assert len(b) > 40


def test_cross_tenant_404_parity(v1, v2):
    assert v1.get("/api/export/workout/999999.itw").status_code == 404
    assert v2.get("/api/export/workout/999999.itw").status_code == 404


def test_zones_parity(v1, v2):
    """First domain-math vertical: identical zone tables from both backends."""
    a = v1.get("/api/athlete/zones")
    b = v2.get("/api/athlete/zones")
    assert a.status_code == b.status_code == 200
    assert a.json() == b.json()


def test_pmc_parity(v1, v2, seeded_metrics):
    """PMC windowing: both backends read the same metrics_daily rows, so the
    windowed response must be byte-identical (shape + window params + rows).
    The 4 backend-v2 unit tests prove the windowing math on 400 seeded rows;
    this proves the two implementations agree on whatever the DB holds
    (seeded_metrics gives them a real 42-day series to agree on)."""
    a = v1.get("/api/metrics/pmc")
    b = v2.get("/api/metrics/pmc")
    assert a.status_code == b.status_code == 200
    assert a.json() == b.json()
    a2 = v1.get("/api/metrics/pmc?days=30")
    b2 = v2.get("/api/metrics/pmc?days=30")
    assert a2.status_code == b2.status_code == 200
    a2j, b2j = a2.json(), b2.json()
    assert a2j == b2j
    assert a2j["window_days"] == 30



def test_readiness_parity(v1, v2, seeded_metrics):
    """Readiness call: identical status/call/level, numeric fields, AND reason
    strings (the hard part — Python vs Java float formatting must match). The
    parity runner seeds a steady load + suppressed-HRV recovery so this
    exercises the ACWR path, the recovery-flag path, and the green→easy merge."""
    a = v1.get("/api/metrics/readiness/today")
    b = v2.get("/api/metrics/readiness/today")
    assert a.status_code == b.status_code == 200
    aj, bj = a.json(), b.json()
    assert aj == bj
    assert aj["status"] == "ok"
    assert aj["call"] == "easy"  # steady load, downgraded by suppressed HRV
    assert any("HRV suppressed" in r for r in aj["reasons"])
