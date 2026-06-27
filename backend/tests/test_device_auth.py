"""Device-pairing → bearer-token auth (native app path)."""

from starlette.testclient import TestClient

from app import auth, repo
from app.main import app


def _run(aid, fn):
    tok = auth.set_current_athlete_id(aid)
    try:
        return fn()
    finally:
        auth.reset_current_athlete_id(tok)


def test_pairing_claim_and_bearer_resolves_athlete():
    aid = repo.find_or_create_athlete(222, "Second")
    code = repo.create_pairing_code(aid, name="iPhone")["code"]
    claimed = repo.claim_pairing_code(code, device_name="iPhone")
    assert claimed is not None
    token = claimed["token"]
    assert claimed["athlete"]["strava_athlete_id"] == 222
    # The bearer resolves to that athlete.
    assert repo.athlete_id_for_bearer(token) == aid
    # And through the real HTTP path: /api/me reflects athlete 222, not the default.
    with TestClient(app) as c:
        me = c.get("/api/me", headers={"Authorization": f"Bearer {token}"}).json()
        assert me["authenticated"] is True
        assert me["athlete"]["strava_athlete_id"] == 222


def test_claim_invalid_code_returns_none_and_400():
    assert repo.claim_pairing_code("deadbeef") is None
    with TestClient(app) as c:
        r = c.post("/api/device/claim", json={"code": "deadbeef"})
        assert r.status_code == 400


def test_claim_is_single_use_and_respects_expiry():
    aid = repo.find_or_create_athlete(333, "Third")
    code = repo.create_pairing_code(aid)["code"]
    assert repo.claim_pairing_code(code) is not None  # first claim works
    assert repo.claim_pairing_code(code) is None       # second is rejected

    expired = repo.create_pairing_code(aid, ttl_s=-1)["code"]
    assert repo.claim_pairing_code(expired) is None     # already expired


def test_bad_bearer_is_ignored():
    assert repo.athlete_id_for_bearer("not-a-real-token") is None


def test_pairing_endpoint_then_plan_itw_over_bearer():
    # A workout for a fresh athlete, reached purely via the bearer token.
    aid = repo.find_or_create_athlete(444, "Fourth")

    def _seed():
        pid = repo.save_plan({"weeks": [], "summary": "S"})
        repo.save_workouts(pid, [
            {"date": "2026-07-06", "sport": "Bike", "title": "Z2",
             "steps": [{"type": "steady", "duration_s": 1800,
                        "target": {"type": "power", "unit": "W", "low": 150, "high": 170}}]},
        ])

    _run(aid, _seed)
    code = repo.create_pairing_code(aid)["code"]
    token = repo.claim_pairing_code(code)["token"]
    with TestClient(app) as c:
        r = c.get("/api/export/plan.itw", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        doc = r.json()
        assert doc["schema_version"] == 1
        titles = [w["title"] for w in doc["workouts"]]
        assert "Z2" in titles
