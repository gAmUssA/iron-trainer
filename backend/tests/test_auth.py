from urllib.parse import parse_qs, urlparse

from starlette.testclient import TestClient

from app import auth, repo
from app.config import get_settings
from app.main import app


def _run(aid, fn):
    tok = auth.set_current_athlete_id(aid)
    try:
        return fn()
    finally:
        auth.reset_current_athlete_id(tok)


def test_local_mode_authenticated_as_default():
    with TestClient(app) as c:
        me = c.get("/api/me").json()
        assert me["auth_required"] is False
        assert me["authenticated"] is True  # default athlete, no login needed
        st = c.get("/api/status").json()
        assert st["authenticated"] is True and st["auth_required"] is False


def test_is_allowed(monkeypatch):
    monkeypatch.setenv("ALLOWED_STRAVA_IDS", "111, 222")
    get_settings.cache_clear()
    assert auth.is_allowed(111) and auth.is_allowed(222)
    assert not auth.is_allowed(999)
    get_settings.cache_clear()


def _act(aid_marker):
    return [{"id": aid_marker, "type": "Run", "sport_type": "Run",
             "start_date_local": "2026-06-01T08:00:00Z", "moving_time": 1800, "distance": 5000}]


def test_per_user_isolation():
    aid2 = repo.find_or_create_athlete(222, "Second")
    assert aid2 != 1

    _run(1, lambda: repo.upsert_activities(_act(101)))
    _run(aid2, lambda: repo.upsert_activities(_act(202)))

    # Each athlete sees only their own activities, plans and metrics.
    assert _run(1, lambda: {a["id"] for a in repo.list_activities()}) == {101}
    assert _run(aid2, lambda: {a["id"] for a in repo.list_activities()}) == {202}
    assert _run(1, repo.activity_count) == 1
    assert _run(aid2, repo.activity_count) == 1

    _run(aid2, lambda: repo.save_plan({"weeks": [], "summary": "B plan"}))
    assert _run(1, repo.get_active_plan) is None
    assert _run(aid2, repo.get_active_plan)["summary"] == "B plan"


def test_get_workout_isolation_blocks_other_user():
    aid2 = repo.find_or_create_athlete(222, "Second")
    pid = _run(aid2, lambda: repo.save_plan({"weeks": [], "summary": "B"}))
    _run(aid2, lambda: repo.save_workouts(pid, [{"date": "2026-07-01", "sport": "Run", "title": "x"}]))
    wid = _run(aid2, lambda: repo.get_workouts(pid))[0]["id"]
    # Athlete 1 cannot read athlete 2's workout.
    assert _run(1, lambda: repo.get_workout(wid)) is None
    assert _run(aid2, lambda: repo.get_workout(wid))["title"] == "x"


def _login_env(monkeypatch, allowed):
    monkeypatch.setenv("AUTH_REQUIRED", "1")
    monkeypatch.setenv("ALLOWED_STRAVA_IDS", allowed)
    monkeypatch.setenv("STRAVA_CLIENT_ID", "x")
    monkeypatch.setenv("STRAVA_CLIENT_SECRET", "y")
    monkeypatch.setenv("SESSION_SECRET", "test-secret")
    get_settings.cache_clear()


def _do_oauth(c, code="abc"):
    r = c.get("/api/strava/connect", follow_redirects=False)
    state = parse_qs(urlparse(r.headers["location"]).query)["state"][0]
    return c.get(f"/api/strava/callback?code={code}&state={state}", follow_redirects=False)


def test_login_flow_allowlisted(monkeypatch):
    _login_env(monkeypatch, "222")
    monkeypatch.setattr("app.strava.exchange_code", lambda code: {
        "access_token": "a", "refresh_token": "r", "expires_at": 9999999999,
        "athlete": {"id": 222, "firstname": "Two", "lastname": "User"}})
    with TestClient(app) as c:
        assert c.get("/api/me").json()["authenticated"] is False  # gated
        assert _do_oauth(c).status_code in (302, 307)
        me = c.get("/api/me").json()
        assert me["authenticated"] is True
        assert me["athlete"]["strava_athlete_id"] == 222
        c.post("/api/auth/logout")
        assert c.get("/api/me").json()["authenticated"] is False
    get_settings.cache_clear()


def test_login_rejected_when_not_allowlisted(monkeypatch):
    _login_env(monkeypatch, "111")  # 222 not allowed
    monkeypatch.setattr("app.strava.exchange_code", lambda code: {"athlete": {"id": 222}})
    with TestClient(app) as c:
        assert _do_oauth(c).status_code == 403
    get_settings.cache_clear()


def test_protected_endpoint_401_when_unauthenticated(monkeypatch):
    _login_env(monkeypatch, "222")
    with TestClient(app) as c:
        # No session → repo.current_athlete_id() raises 401 on a per-user endpoint.
        assert c.get("/api/activities").status_code == 401
    get_settings.cache_clear()
