"""Backend-driven chart windows: /metrics/pmc and /metrics/trends `days` param."""

from datetime import date, timedelta

from starlette.testclient import TestClient

from app import repo
from app.main import app

TODAY = date.today()


def _metric_rows(n_days: int) -> list[dict]:
    return [
        {
            "date": (TODAY - timedelta(days=ago)).isoformat(),
            "tss": 50.0, "ctl": 60.0, "atl": 55.0, "tsb": 5.0,
        }
        for ago in range(n_days, 0, -1)
    ]


def test_pmc_defaults_to_recent_window(monkeypatch):
    monkeypatch.setattr(repo, "get_metrics", lambda: _metric_rows(400))
    with TestClient(app) as c:
        body = c.get("/api/metrics/pmc").json()
        assert body["total_days"] == 400
        assert body["window_days"] == 180
        assert len(body["days"]) == 180
        assert body["days"][0]["date"] >= (TODAY - timedelta(days=180)).isoformat()


def test_pmc_zero_means_full_history(monkeypatch):
    monkeypatch.setattr(repo, "get_metrics", lambda: _metric_rows(400))
    with TestClient(app) as c:
        body = c.get("/api/metrics/pmc?days=0").json()
        assert len(body["days"]) == 400


def test_pmc_rejects_negative_days():
    with TestClient(app) as c:
        assert c.get("/api/metrics/pmc?days=-5").status_code == 422


def test_trends_windows_points_but_not_insights(monkeypatch):
    old = (TODAY - timedelta(days=300)).isoformat()
    recent = (TODAY - timedelta(days=10)).isoformat()

    def _acts():
        return [
            {"start_date": f"{old}T07:00:00+00:00", "sport": "Run",
             "moving_time": 3600, "distance": 10000, "avg_hr": 150},
            {"start_date": f"{recent}T07:00:00+00:00", "sport": "Run",
             "moving_time": 3600, "distance": 10500, "avg_hr": 148},
        ]

    monkeypatch.setattr(repo, "list_activities", _acts)
    with TestClient(app) as c:
        body = c.get("/api/metrics/trends").json()
        # Chart points: only the recent run survives the default window.
        assert [p["date"] for p in body["Run"]] == [recent]
        # Insights still saw the full record (freshness, PRs etc. intact).
        assert body["insights"]["freshness"]["last_activity"] == recent
        assert body["window_days"] == 180

        full = c.get("/api/metrics/trends?days=0").json()
        assert [p["date"] for p in full["Run"]] == [old, recent]
