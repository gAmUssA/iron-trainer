import pytest
from starlette.testclient import TestClient

from app import repo
from app.main import app

SEED = [
    {"slug": "im703-test", "name": "IRONMAN 70.3 Test", "date": "2026-09-26",
     "distance": "70.3", "city": "Test", "country": "United States"},
    {"slug": "im-test", "name": "IRONMAN Test", "date": "2026-11-15",
     "distance": "140.6", "city": "Test", "country": "United States"},
]


def test_cutoffs_for_distance():
    assert repo.cutoffs_for("70.3") == (4200, 19800, 30600)
    assert repo.cutoffs_for("140.6") == (8400, 37800, 61200)
    assert repo.cutoffs_for(None) == (4200, 19800, 30600)  # default 70.3


def test_seed_races_idempotent():
    assert repo.seed_races(SEED) == 2
    assert repo.seed_races(SEED) == 2  # re-seed updates, no duplicates
    assert len(repo.list_races()) == 2
    assert len(repo.list_races(distance="70.3")) == 1
    full = repo.list_races(distance="140.6")[0]
    assert full["cutoff_finish_s"] == 61200  # derived from distance


def test_list_races_filters():
    repo.seed_races(SEED)
    assert repo.list_races(q="70.3 test")[0]["slug"] == "im703-test"
    assert {r["slug"] for r in repo.list_races(month="2026-11")} == {"im-test"}


def test_effective_race_default_then_override():
    # No athlete race → env default.
    eff = repo.effective_race()
    assert eff["name"] and eff["cutoff_finish_s"] == 30600
    # Select a full-distance catalog race → cut-offs flip.
    repo.seed_races(SEED)
    full = repo.list_races(distance="140.6")[0]
    repo.set_athlete_race(race_id=full["id"])
    eff = repo.effective_race()
    assert eff["name"] == "IRONMAN Test"
    assert eff["distance"] == "140.6"
    assert eff["cutoff_finish_s"] == 61200


def test_custom_race_and_validation():
    eff = repo.set_athlete_race(name="My Race", race_date="2026-12-06", distance="140.6")
    assert eff["cutoff_swim_s"] == 8400
    assert repo.effective_race()["name"] == "My Race"
    with pytest.raises(ValueError):
        repo.set_athlete_race()  # neither id nor name/date


def test_endpoints_status_and_readiness_reflect_selection():
    repo.seed_races(SEED)
    with TestClient(app) as c:
        target = next(r for r in c.get("/api/races?distance=140.6").json()["races"] if r["slug"] == "im-test")
        c.put("/api/athlete/race", json={"race_id": target["id"]})
        st = c.get("/api/status").json()["race"]
        assert st["name"] == "IRONMAN Test" and st["distance"] == "140.6"
        finish = next(x for x in c.get("/api/metrics/readiness").json()["cutoffs"] if x["checkpoint"] == "Finish")
        assert finish["limit_s"] == 61200  # full-distance finish cut-off
