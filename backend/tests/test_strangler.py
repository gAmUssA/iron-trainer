"""Config-driven strangler proxy: the path matcher and the generalized
middleware (ANY allowlisted bearer GET proxies, not just exports; the flip is a
config change). The export-specific fallback/rollback cases live in
test_export_proxy.py."""

import httpx
import pytest
from starlette.testclient import TestClient

from app import strangler
from app.config import get_settings
from app.main import app
from app.strangler import path_matches


def test_path_matches_exact_and_prefix():
    pats = ["/api/export/workout/*", "/api/export/plan.itw"]
    assert path_matches("/api/export/workout/5.fit", pats)
    assert path_matches("/api/export/workout/5.zwo", pats)
    assert path_matches("/api/export/plan.itw", pats)
    # Siblings backend-v2 does NOT own must not match:
    assert not path_matches("/api/export/plan.zip", pats)
    assert not path_matches("/api/export/week/2026-01-05.zip", pats)
    assert not path_matches("/api/metrics/readiness/today", pats)


def test_default_allowlist_is_export_only():
    get_settings.cache_clear()
    assert get_settings().proxy_path_list == [
        "/api/export/workout/*", "/api/export/plan.itw"]


@pytest.fixture()
def flipped_readiness(monkeypatch):
    monkeypatch.setenv("EXPORT_PROXY_URL", "http://backend-v2.internal:8080")
    monkeypatch.setenv("PROXY_PATHS", "/api/metrics/readiness/today")
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def _record(monkeypatch, body=b'{"status":"ok"}'):
    rec: dict = {}

    async def fake(url, authz):
        rec["url"] = url
        rec["authz"] = authz
        return httpx.Response(200, content=body,
                              headers={"content-type": "application/json"})

    monkeypatch.setattr(strangler, "fetch", fake)
    return rec


def test_configured_vertical_proxies(flipped_readiness, monkeypatch):
    """Adding a path to PROXY_PATHS flips that vertical — no code change."""
    rec = _record(monkeypatch)
    with TestClient(app) as c:
        r = c.get("/api/metrics/readiness/today",
                  headers={"Authorization": "Bearer tok"})
        assert r.status_code == 200
        assert rec["url"] == "http://backend-v2.internal:8080/api/metrics/readiness/today"
        assert rec["authz"] == "Bearer tok"
        assert r.json()["status"] == "ok"


def test_unlisted_path_not_proxied(flipped_readiness, monkeypatch):
    """EXPORT_PROXY_URL set + bearer, but the export path is not in PROXY_PATHS
    (only readiness is), so it must serve locally."""
    async def boom(*a, **k):
        raise AssertionError("proxied an unlisted path")

    monkeypatch.setattr(strangler, "fetch", boom)
    with TestClient(app) as c:
        c.put("/api/athlete/profile", json={"ftp": 228, "weekly_hours_target": 7})
        c.post("/api/plan/generate?use_llm=false")
        r = c.get("/api/export/plan.itw", headers={"Authorization": "Bearer tok"})
        assert r.status_code == 200
        assert r.json()["generator"] == "iron-trainer"


def test_non_get_never_proxies(flipped_readiness, monkeypatch):
    """Even an allowlisted path must not proxy a write — the middleware guards
    on method before anything else (a POST reaches routing → 405, not the proxy)."""
    async def boom(*a, **k):
        raise AssertionError("proxied a non-GET request")

    monkeypatch.setattr(strangler, "fetch", boom)
    with TestClient(app) as c:
        r = c.post("/api/metrics/readiness/today",
                   headers={"Authorization": "Bearer tok"})
        assert r.status_code == 405  # no POST route; proxy was skipped, not hit


def test_query_string_forwarded(monkeypatch):
    monkeypatch.setenv("EXPORT_PROXY_URL", "http://backend-v2.internal:8080")
    monkeypatch.setenv("PROXY_PATHS", "/api/metrics/pmc")
    get_settings.cache_clear()
    rec = _record(monkeypatch, body=b'{"window_days":30}')
    try:
        with TestClient(app) as c:
            c.get("/api/metrics/pmc?days=30", headers={"Authorization": "Bearer tok"})
            assert rec["url"] == "http://backend-v2.internal:8080/api/metrics/pmc?days=30"
    finally:
        get_settings.cache_clear()
