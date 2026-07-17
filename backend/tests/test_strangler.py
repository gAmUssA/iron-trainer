"""Config-driven strangler proxy: the path matcher and the generalized
middleware (ANY allowlisted bearer GET proxies, not just exports; the flip is a
config change). Export-specific fallback/rollback cases live in
test_export_proxy.py; the recording-stub / block-proxy helpers are shared
fixtures in conftest.py."""

import json

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


def test_configured_vertical_proxies(flipped_readiness, proxy_stub):
    """Adding a path to PROXY_PATHS flips that vertical — no code change."""
    rec = proxy_stub(content=b'{"status":"ok"}')
    with TestClient(app) as c:
        r = c.get("/api/metrics/readiness/today",
                  headers={"Authorization": "Bearer tok"})
        assert r.status_code == 200
        assert rec["url"] == "http://backend-v2.internal:8080/api/metrics/readiness/today"
        assert rec["authz"] == "Bearer tok"
        assert r.json()["status"] == "ok"


def test_unlisted_path_not_proxied(flipped_readiness, block_proxy):
    """EXPORT_PROXY_URL set + bearer, but the export path is not in PROXY_PATHS
    (only readiness is), so it must serve locally."""
    block_proxy()
    with TestClient(app) as c:
        c.put("/api/athlete/profile", json={"ftp": 228, "weekly_hours_target": 7})
        c.post("/api/plan/generate?use_llm=false")
        r = c.get("/api/export/plan.itw", headers={"Authorization": "Bearer tok"})
        assert r.status_code == 200
        assert r.json()["generator"] == "iron-trainer"


def test_write_to_read_only_flip_stays_local(flipped_readiness, block_proxy):
    """A path flipped for READS (in PROXY_PATHS) does not proxy its writes: the
    write allowlist (PROXY_WRITE_PATHS) is separate and empty here, so a POST
    reaches local routing → 405 (no POST route), never the proxy."""
    block_proxy()
    with TestClient(app) as c:
        r = c.post("/api/metrics/readiness/today",
                   headers={"Authorization": "Bearer tok"})
        assert r.status_code == 405  # no POST route; proxy was skipped, not hit


def test_query_string_forwarded(monkeypatch, proxy_stub):
    monkeypatch.setenv("EXPORT_PROXY_URL", "http://backend-v2.internal:8080")
    monkeypatch.setenv("PROXY_PATHS", "/api/metrics/pmc")
    get_settings.cache_clear()
    rec = proxy_stub(content=b'{"window_days":30}')
    try:
        with TestClient(app) as c:
            c.get("/api/metrics/pmc?days=30", headers={"Authorization": "Bearer tok"})
            assert rec["url"] == "http://backend-v2.internal:8080/api/metrics/pmc?days=30"
    finally:
        get_settings.cache_clear()


def test_multivalue_response_headers_preserved(flipped_readiness, monkeypatch):
    """A proxied response with repeated headers (e.g. two Set-Cookie) must keep
    ALL of them — a dict would collapse to the last value."""
    async def fake(url, authz):
        return httpx.Response(
            200, content=b'{"ok":true}',
            headers=[("content-type", "application/json"),
                     ("set-cookie", "a=1; Path=/"),
                     ("set-cookie", "b=2; Path=/")],
        )

    monkeypatch.setattr(strangler, "fetch", fake)
    with TestClient(app) as c:
        r = c.get("/api/metrics/readiness/today",
                  headers={"Authorization": "Bearer tok"})
        cookies = r.headers.get_list("set-cookie")
        assert len(cookies) == 2
        assert any("a=1" in c for c in cookies)
        assert any("b=2" in c for c in cookies)


# ── Write forwarding (PROXY_WRITE_PATHS) ──────────────────────────────────────


@pytest.fixture()
def flipped_tests_write(monkeypatch):
    """Flip the fitness-test write endpoint to backend-v2."""
    monkeypatch.setenv("EXPORT_PROXY_URL", "http://backend-v2.internal:8080")
    monkeypatch.setenv("PROXY_WRITE_PATHS", "/api/tests/result")
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def _stub_write(monkeypatch, *, status=200, content=b'{"ok":true}', exc=None):
    """Record what the proxy forwarded on the write path (url/method/body/headers)."""
    rec: dict = {}

    async def fake(url, method, body, headers):
        rec.update(url=url, method=method, body=body, headers=headers)
        if exc is not None:
            raise exc
        return httpx.Response(status, content=content,
                              headers={"content-type": "application/json"})

    monkeypatch.setattr(strangler, "fetch_write", fake)
    return rec


def test_write_in_allowlist_proxies_with_bearer(flipped_tests_write, monkeypatch):
    """A POST on the write allowlist forwards method + body + Authorization."""
    rec = _stub_write(monkeypatch)
    with TestClient(app) as c:
        r = c.post("/api/tests/result", headers={"Authorization": "Bearer tok"},
                   json={"test_slug": "ftp20", "inputs": {"x": 1}})
        assert r.status_code == 200
        assert r.json()["ok"] is True
        assert rec["url"] == "http://backend-v2.internal:8080/api/tests/result"
        assert rec["method"] == "POST"
        assert json.loads(rec["body"]) == {"test_slug": "ftp20", "inputs": {"x": 1}}
        fwd = {k.lower(): v for k, v in rec["headers"].items()}
        assert fwd["authorization"] == "Bearer tok"


def test_write_forwards_session_cookie(flipped_tests_write, monkeypatch):
    """A cookie-only (web) write is now proxy-eligible; the Cookie header is
    forwarded so backend-v2 can verify the session (ADR 0022)."""
    rec = _stub_write(monkeypatch)
    with TestClient(app) as c:
        r = c.post("/api/tests/result", headers={"Cookie": "session=abc.def.ghi"},
                   json={"test_slug": "ftp20", "inputs": {}})
        assert r.status_code == 200
        fwd = {k.lower(): v for k, v in rec["headers"].items()}
        assert fwd["cookie"] == "session=abc.def.ghi"
        assert "authorization" not in fwd


def test_write_unauthenticated_stays_local(flipped_tests_write, monkeypatch):
    """No bearer and no session cookie → not proxy-eligible; served locally with
    its body intact (local validation → 422), and fetch_write is never called."""
    async def boom(*a, **k):
        raise AssertionError("must not proxy an unauthenticated write")

    monkeypatch.setattr(strangler, "fetch_write", boom)
    with TestClient(app) as c:
        r = c.post("/api/tests/result", json={})
        assert r.status_code == 422  # local Pydantic validation


def test_write_not_in_write_allowlist_stays_local(monkeypatch):
    """A path flipped only for reads (PROXY_PATHS) does not proxy its writes."""
    monkeypatch.setenv("EXPORT_PROXY_URL", "http://backend-v2.internal:8080")
    monkeypatch.setenv("PROXY_PATHS", "/api/tests/result")  # read allowlist only
    get_settings.cache_clear()

    async def boom(*a, **k):
        raise AssertionError("write must not proxy when not in PROXY_WRITE_PATHS")

    monkeypatch.setattr(strangler, "fetch_write", boom)
    try:
        with TestClient(app) as c:
            r = c.post("/api/tests/result", headers={"Authorization": "Bearer tok"},
                       json={})
            assert r.status_code == 422  # local
    finally:
        get_settings.cache_clear()


def test_write_5xx_forwarded_not_retried_locally(flipped_tests_write, monkeypatch):
    """A 5xx from backend-v2 on a write is forwarded to the client, NOT retried
    locally — backend-v2 may have committed, and a local retry would double-apply.
    The empty body would 422 locally, so a 500 response proves no fallback."""
    _stub_write(monkeypatch, status=500, content=b'{"detail":"boom"}')
    with TestClient(app) as c:
        r = c.post("/api/tests/result", headers={"Authorization": "Bearer tok"},
                   json={})
        assert r.status_code == 500
        assert r.json()["detail"] == "boom"


def test_write_connect_error_falls_back_local(flipped_tests_write, monkeypatch):
    """An UNREACHABLE backend (connect error, request never delivered) falls back
    locally, replaying the buffered body — safe, since nothing was committed. The
    replayed empty body hits local validation → 422 (not the 502 no-fallback path)."""
    _stub_write(monkeypatch, exc=httpx.ConnectError("connection refused"))
    with TestClient(app) as c:
        r = c.post("/api/tests/result", headers={"Authorization": "Bearer tok"},
                   json={})
        assert r.status_code == 422  # served locally with the replayed body


def test_write_read_timeout_returns_502_no_fallback(flipped_tests_write, monkeypatch):
    """A post-send failure (e.g. read timeout — request WAS delivered) is not
    retried locally; the client gets a 502 rather than risk a double-apply."""
    _stub_write(monkeypatch, exc=httpx.ReadTimeout("timed out"))
    with TestClient(app) as c:
        r = c.post("/api/tests/result", headers={"Authorization": "Bearer tok"},
                   json={})
        assert r.status_code == 502


def test_write_pool_timeout_falls_back_local(flipped_tests_write, monkeypatch):
    """A PoolTimeout (connection never acquired → request never delivered) is a
    safe local fallback, not a 502 — nothing was committed."""
    _stub_write(monkeypatch, exc=httpx.PoolTimeout("pool exhausted"))
    with TestClient(app) as c:
        r = c.post("/api/tests/result", headers={"Authorization": "Bearer tok"},
                   json={})
        assert r.status_code == 422  # served locally with the replayed body


def test_write_non_session_cookie_stays_local(flipped_tests_write, monkeypatch):
    """A cookie whose name merely ENDS in 'session' (e.g. websession) must not
    make an otherwise-unauthenticated write proxy-eligible."""
    async def boom(*a, **k):
        raise AssertionError("a non-'session' cookie must not flip a write")

    monkeypatch.setattr(strangler, "fetch_write", boom)
    with TestClient(app) as c:
        r = c.post("/api/tests/result", headers={"Cookie": "websession=x"}, json={})
        assert r.status_code == 422  # local (not proxy-eligible)


def test_write_forwards_content_encoding_and_extra_headers(flipped_tests_write, monkeypatch):
    """Headers beyond the auth trio (Content-Encoding, Idempotency-Key, ...) are
    forwarded; host/content-length are not."""
    rec = _stub_write(monkeypatch)
    with TestClient(app) as c:
        c.post("/api/tests/result",
               headers={"Authorization": "Bearer tok",
                        "Content-Encoding": "gzip",
                        "Idempotency-Key": "abc123"},
               content=b'{"test_slug":"ftp20","inputs":{}}')
        fwd = {k.lower(): v for k, v in rec["headers"].items()}
        assert fwd.get("content-encoding") == "gzip"
        assert fwd.get("idempotency-key") == "abc123"
        assert "host" not in fwd
        assert "content-length" not in fwd


def test_read_body_raises_on_premature_disconnect():
    """A mid-upload disconnect must raise, never return a truncated body."""
    import asyncio

    from starlette.requests import ClientDisconnect

    async def receive():
        # first chunk with more_body=True, then the client drops
        if not getattr(receive, "sent", False):
            receive.sent = True
            return {"type": "http.request", "body": b"partial", "more_body": True}
        return {"type": "http.disconnect"}

    with pytest.raises(ClientDisconnect):
        asyncio.run(strangler._read_body(receive))
