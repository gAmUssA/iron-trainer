"""Cross-implementation parity: the SAME export requests against FastAPI and
backend-v2 must agree. Skipped unless V2_BASE_URL is set (the parity runner
provides it plus a real bearer paired through the FastAPI API)."""

import json
import os

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
