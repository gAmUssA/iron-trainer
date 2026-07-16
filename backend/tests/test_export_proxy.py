"""Strangler flip via the config-driven proxy middleware: bearer export traffic
proxies to backend-v2; cookie traffic and proxy failures stay / fall back local.
(The generalized matcher + non-export verticals are covered in test_strangler.py.)"""

import httpx
import pytest
from starlette.testclient import TestClient

from app import strangler
from app.config import get_settings
from app.main import app


@pytest.fixture()
def flipped(monkeypatch):
    monkeypatch.setenv("EXPORT_PROXY_URL", "http://backend-v2.internal:8080")
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def _stub(monkeypatch, *, content=b'{"schema_version": 1}', status=200,
          ct="application/json") -> dict:
    """Replace the network with a recording stub; returns the record dict."""
    rec: dict = {}

    async def fake(url, authz):
        rec["url"] = url
        rec["authz"] = authz
        return httpx.Response(status, content=content, headers={"content-type": ct})

    monkeypatch.setattr(strangler, "fetch", fake)
    return rec


def _no_proxy(monkeypatch):
    async def boom(*a, **k):
        raise AssertionError("proxy called when it must serve locally")

    monkeypatch.setattr(strangler, "fetch", boom)


def _seed_plan(c):
    c.put("/api/athlete/profile", json={"ftp": 228, "weekly_hours_target": 7})
    c.post("/api/plan/generate?use_llm=false")


def test_bearer_export_proxies(flipped, monkeypatch):
    rec = _stub(monkeypatch)
    with TestClient(app) as c:
        r = c.get("/api/export/plan.itw", headers={"Authorization": "Bearer tok123"})
        assert r.status_code == 200
        assert rec["url"] == "http://backend-v2.internal:8080/api/export/plan.itw"
        assert rec["authz"] == "Bearer tok123"
        assert r.json()["schema_version"] == 1


def test_cookie_traffic_stays_local(flipped, monkeypatch):
    _no_proxy(monkeypatch)
    with TestClient(app) as c:
        _seed_plan(c)
        r = c.get("/api/export/plan.itw")  # no bearer → local
        assert r.status_code == 200
        assert r.json()["generator"] == "iron-trainer"


def test_proxy_failure_falls_back_local(flipped, monkeypatch):
    async def down(*a, **k):
        raise httpx.ConnectError("backend-v2 down")

    monkeypatch.setattr(strangler, "fetch", down)
    with TestClient(app) as c:
        _seed_plan(c)
        r = c.get("/api/export/plan.itw", headers={"Authorization": "Bearer tok123"})
        assert r.status_code == 200  # yesterday's behavior, preserved
        assert r.json()["generator"] == "iron-trainer"


def test_flip_off_is_default_local(monkeypatch):
    _no_proxy(monkeypatch)
    with TestClient(app) as c:
        _seed_plan(c)
        assert c.get("/api/export/plan.itw",
                     headers={"Authorization": "Bearer tok123"}).status_code == 200
