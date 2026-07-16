"""Strangler flip via the config-driven proxy middleware: bearer export traffic
proxies to backend-v2; cookie traffic and proxy failures stay / fall back local.
(The generalized matcher + non-export verticals are covered in test_strangler.py.
The recording stub / block-proxy helpers are shared fixtures in conftest.py.)"""

import httpx
import pytest
from starlette.testclient import TestClient

from app.config import get_settings
from app.main import app


@pytest.fixture()
def flipped(monkeypatch):
    monkeypatch.setenv("EXPORT_PROXY_URL", "http://backend-v2.internal:8080")
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def _seed_plan(c):
    c.put("/api/athlete/profile", json={"ftp": 228, "weekly_hours_target": 7})
    c.post("/api/plan/generate?use_llm=false")


def test_bearer_export_proxies(flipped, proxy_stub):
    rec = proxy_stub()
    with TestClient(app) as c:
        r = c.get("/api/export/plan.itw", headers={"Authorization": "Bearer tok123"})
        assert r.status_code == 200
        assert rec["url"] == "http://backend-v2.internal:8080/api/export/plan.itw"
        assert rec["authz"] == "Bearer tok123"
        assert r.json()["schema_version"] == 1


def test_cookie_traffic_stays_local(flipped, block_proxy):
    block_proxy()
    with TestClient(app) as c:
        _seed_plan(c)
        r = c.get("/api/export/plan.itw")  # no bearer → local
        assert r.status_code == 200
        assert r.json()["generator"] == "iron-trainer"


def test_proxy_failure_falls_back_local(flipped, block_proxy):
    block_proxy(httpx.ConnectError("backend-v2 down"))
    with TestClient(app) as c:
        _seed_plan(c)
        r = c.get("/api/export/plan.itw", headers={"Authorization": "Bearer tok123"})
        assert r.status_code == 200  # yesterday's behavior, preserved
        assert r.json()["generator"] == "iron-trainer"


def test_backend_5xx_falls_back_local(flipped, proxy_stub):
    # A backend-v2 malfunction (500) must not reach the client — serve locally.
    proxy_stub(content=b"upstream boom", status=500, ct="text/plain")
    with TestClient(app) as c:
        _seed_plan(c)
        r = c.get("/api/export/plan.itw", headers={"Authorization": "Bearer tok123"})
        assert r.status_code == 200
        assert r.json()["generator"] == "iron-trainer"


def test_flip_off_is_default_local(block_proxy):
    block_proxy()
    with TestClient(app) as c:
        _seed_plan(c)
        assert c.get("/api/export/plan.itw",
                     headers={"Authorization": "Bearer tok123"}).status_code == 200
