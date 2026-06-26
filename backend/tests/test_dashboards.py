from datetime import date

from starlette.testclient import TestClient

from app import analysis, repo
from app.main import app
from tests.test_pipeline import _fake_activities


def _seed():
    today = date(2026, 6, 25)
    repo.upsert_activities(_fake_activities(today))
    profile = analysis.infer_profile(repo.list_activities(), today=today)
    repo.save_profile(profile.as_dict())
    repo.recompute_tss()
    repo.rebuild_metrics(today=today)


def test_dashboard_endpoints():
    _seed()
    with TestClient(app) as c:
        weekly = c.get("/api/metrics/weekly").json()["weeks"]
        assert weekly and "by_sport" in weekly[-1]
        assert weekly[-1]["total_hours"] > 0

        trends = c.get("/api/metrics/trends").json()
        assert trends["Bike"] and "power" in trends["Bike"][0]
        assert trends["Run"] and "pace" in trends["Run"][0]

        readiness = c.get("/api/metrics/readiness").json()
        assert readiness["total"] is not None
        # Sanity: a 70.3 finish lands somewhere between 4h and 9h.
        assert 4 * 3600 < readiness["total"]["seconds"] < 9 * 3600
        assert "swim" in readiness["legs"]
        assert "bike" in readiness["legs"]
        assert "run" in readiness["legs"]

        # Cut-offs: three cumulative checkpoints with the standard 70.3 limits.
        checks = {c["checkpoint"]: c for c in readiness["cutoffs"]}
        assert set(checks) == {"Swim", "Bike", "Finish"}
        assert checks["Swim"]["limit_s"] == 70 * 60
        assert checks["Bike"]["limit_s"] == 330 * 60
        assert checks["Finish"]["limit_s"] == 510 * 60
        # A fit demo athlete should be projected to make the finish cut-off.
        assert checks["Finish"]["ok"] is True
        # Bike checkpoint is cumulative (> the standalone bike leg).
        assert checks["Bike"]["projected_s"] > readiness["legs"]["bike"]["seconds"]


def test_readiness_scales_with_race_distance():
    from app import dashboards
    from app.metrics import Thresholds

    repo.upsert_activities(_fake_activities(date.today()))  # recent rides with avg_speed
    th = Thresholds(css_swim=100, threshold_pace_run=300)
    acts = repo.list_activities()
    half = dashboards.race_readiness(acts, th, current_ctl=None, distance="70.3")
    full = dashboards.race_readiness(acts, th, current_ctl=None, distance="140.6")
    # 140.6 is ~2x the leg distances → finish ~2x the half.
    assert full["total"]["seconds"] > half["total"]["seconds"] * 1.8
    assert full["legs"]["bike"]["seconds"] > half["legs"]["bike"]["seconds"] * 1.8
