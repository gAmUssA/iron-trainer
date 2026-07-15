"""Strangler flip: bearer export traffic proxies to backend-v2; cookie traffic
and proxy failures stay/fall back local."""

import httpx
import pytest
from starlette.testclient import TestClient

from app.config import get_settings
from app.main import app
from app.routers import export_router


@pytest.fixture()
def flipped(monkeypatch):
    monkeypatch.setenv("EXPORT_PROXY_URL", "http://backend-v2.internal:8080")
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def _seed_plan(c):
    c.put("/api/athlete/profile", json={"ftp": 228, "weekly_hours_target": 7})
    c.post("/api/plan/generate?use_llm=false")


def test_bearer_request_proxies(flipped, monkeypatch):
    calls = {}

    def fake_get(url, headers=None, timeout=None):
        calls["url"] = url
        calls["auth"] = headers.get("Authorization")
        return httpx.Response(200, content=b'{"schema_version": 1}',
                              headers={"content-type": "application/json"})

    monkeypatch.setattr(export_router.httpx, "get", fake_get)
    with TestClient(app) as c:
        r = c.get("/api/export/plan.itw", headers={"Authorization": "Bearer tok123"})
        assert r.status_code == 200
        assert calls["url"] == "http://backend-v2.internal:8080/api/export/plan.itw"
        assert calls["auth"] == "Bearer tok123"
        assert r.json()["schema_version"] == 1


def test_cookie_traffic_stays_local(flipped, monkeypatch):
    def boom(*a, **k):  # local path must never call the proxy
        raise AssertionError("proxy called for session traffic")

    monkeypatch.setattr(export_router.httpx, "get", boom)
    with TestClient(app) as c:
        _seed_plan(c)
        r = c.get("/api/export/plan.itw")  # no bearer → local
        assert r.status_code == 200
        assert r.json()["generator"] == "iron-trainer"


def test_proxy_failure_falls_back_local(flipped, monkeypatch):
    def down(*a, **k):
        raise httpx.ConnectError("backend-v2 down")

    monkeypatch.setattr(export_router.httpx, "get", down)
    with TestClient(app) as c:
        _seed_plan(c)
        r = c.get("/api/export/plan.itw", headers={"Authorization": "Bearer tok123"})
        assert r.status_code == 200  # yesterday's behavior, preserved
        assert r.json()["generator"] == "iron-trainer"


def test_flip_off_is_default_local(monkeypatch):
    def boom(*a, **k):
        raise AssertionError("proxy called with flip off")

    monkeypatch.setattr(export_router.httpx, "get", boom)
    with TestClient(app) as c:
        _seed_plan(c)
        assert c.get("/api/export/plan.itw",
                     headers={"Authorization": "Bearer tok123"}).status_code == 200
