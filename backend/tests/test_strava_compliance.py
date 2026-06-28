"""Strava OAuth UX hardening + disconnect/data-deletion (API agreement §7.4/§2.5)."""

from urllib.parse import parse_qs, urlparse

from starlette.testclient import TestClient

from app import repo
from app.config import get_settings
from app.main import app


def _login_env(monkeypatch, allowed="222"):
    monkeypatch.setenv("AUTH_REQUIRED", "1")
    monkeypatch.setenv("ALLOWED_STRAVA_IDS", allowed)
    monkeypatch.setenv("STRAVA_CLIENT_ID", "x")
    monkeypatch.setenv("STRAVA_CLIENT_SECRET", "y")
    monkeypatch.setenv("SESSION_SECRET", "test-secret")
    get_settings.cache_clear()


def _state(c):
    r = c.get("/api/strava/connect", follow_redirects=False)
    return parse_qs(urlparse(r.headers["location"]).query)["state"][0]


# --- OAuth UX hardening: failures redirect to the SPA, never a raw error page ----

def test_callback_denied_redirects_with_flag(monkeypatch):
    _login_env(monkeypatch)
    with TestClient(app) as c:
        r = c.get("/api/strava/callback?error=access_denied", follow_redirects=False)
        assert r.status_code in (302, 307)
        assert "strava_error=access_denied" in r.headers["location"]
    get_settings.cache_clear()


def test_callback_exchange_failure_redirects(monkeypatch):
    _login_env(monkeypatch)

    def boom(code):
        import httpx
        raise httpx.HTTPError("strava down")

    monkeypatch.setattr("app.strava.exchange_code", boom)
    with TestClient(app) as c:
        state = _state(c)
        r = c.get(f"/api/strava/callback?code=abc&state={state}", follow_redirects=False)
        assert r.status_code in (302, 307)
        assert "strava_error=exchange_failed" in r.headers["location"]
        assert c.get("/api/me").json()["authenticated"] is False
    get_settings.cache_clear()


# --- Disconnect + data deletion -------------------------------------------------

def test_disconnect_deletes_data_and_clears_tokens():
    """Local mode: connect data for the default athlete, then disconnect purges it."""
    repo.save_tokens(1, {"access_token": "a", "refresh_token": "r", "expires_at": 9999999999,
                         "athlete": {"id": 4242, "firstname": "Test"}})
    repo.upsert_activities([{"id": 700, "type": "Run", "sport_type": "Run",
                             "start_date_local": "2026-06-01T08:00:00Z",
                             "moving_time": 1800, "distance": 5000}])
    repo.rebuild_metrics()
    repo.save_profile({"ftp": 250})  # manually-entered threshold — must survive

    assert repo.activity_count() == 1

    with TestClient(app) as c:
        out = c.post("/api/strava/disconnect").json()

    assert out["deleted_activities"] == 1
    assert out["deleted_metrics"] >= 1
    assert "deleted" in out["message"].lower()
    # Activities + tokens gone; the athlete row + manual FTP remain.
    assert repo.activity_count() == 0
    a = repo.get_athlete()
    assert a.get("strava_refresh_token") is None
    assert a.get("strava_athlete_id") is None
    assert a.get("ftp") == 250
