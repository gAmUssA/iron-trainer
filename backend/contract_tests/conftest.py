"""Black-box contract suite: plain HTTP against ANY running Iron Trainer
backend (CONTRACT_BASE_URL). No app imports, no monkeypatching, no DB access —
if a test can't be expressed through the public API, it doesn't belong here.

This is the cross-implementation harness for the Quarkus migration: run it
against the FastAPI app today and the Quarkus app per-vertical tomorrow; both
must pass identically.

Local run:  ./contract_tests/run_local.sh
Explicit:   CONTRACT_BASE_URL=http://127.0.0.1:8123 uv run pytest contract_tests -q
"""

import os

import httpx
import pytest

BASE_URL = os.environ.get("CONTRACT_BASE_URL", "http://127.0.0.1:8123")


@pytest.fixture(scope="session")
def api() -> httpx.Client:
    client = httpx.Client(base_url=BASE_URL, timeout=60.0, follow_redirects=True)
    try:
        client.get("/api/health").raise_for_status()
    except Exception as e:  # pragma: no cover
        pytest.exit(f"No backend at {BASE_URL} ({e}) — start one or set CONTRACT_BASE_URL", 1)
    yield client
    client.close()


@pytest.fixture(scope="session")
def seeded(api: httpx.Client) -> httpx.Client:
    """Seed via the public API only: thresholds + a template plan."""
    api.put("/api/athlete/profile", json={
        "ftp": 228, "threshold_hr": 160, "max_hr": 182,
        "threshold_pace_run": 300, "css_swim": 105, "weekly_hours_target": 8,
    }).raise_for_status()
    api.post("/api/plan/generate?use_llm=false").raise_for_status()
    return api
