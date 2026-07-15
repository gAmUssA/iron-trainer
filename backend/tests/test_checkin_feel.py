"""Feel-vs-data check-in: sanitization, reconciliation line, persistence,
compounding history, and the endpoint body."""

from datetime import date, timedelta

from starlette.testclient import TestClient

from app import repo
from app.main import app
from app.planning import service

TODAY = date.today()


def _metrics(week_tss: float, *, tsb: float = 0.0, days: int = 42) -> list[dict]:
    per_day = week_tss / 7.0
    return [
        {"date": (TODAY - timedelta(days=ago)).isoformat(),
         "tss": per_day, "ctl": 60.0, "atl": 55.0, "tsb": tsb}
        for ago in range(days, 0, -1)
    ]


def test_sanitize_feel_clamps_and_filters():
    out = service.sanitize_feel({
        "energy": 9, "sleep": 0, "body": 3.7, "stress": "nope",
        "note": "  left calf tight  " + "x" * 400, "bogus": 5,
    })
    assert out["energy"] == 5 and out["sleep"] == 1 and out["body"] == 3
    assert "stress" not in out and "bogus" not in out
    assert out["note"].startswith("left calf tight") and len(out["note"]) <= 280
    assert service.sanitize_feel(None) is None
    assert service.sanitize_feel({"note": "   "}) is None


def test_feel_vs_data_disagreement_lines():
    green = {"status": "ok", "level": "green", "reasons": ["Load is steady."]}
    amber = {"status": "ok", "level": "amber", "reasons": ["Load is ramping fast."]}
    rough = {"energy": 2, "sleep": 2, "body": 2, "stress": 2}
    great = {"energy": 5, "sleep": 4, "body": 4, "stress": 5}

    line = service._feel_vs_data_line(rough, green)
    assert "sleep or life stress" in line

    line = service._feel_vs_data_line(great, amber)
    assert "numbers disagree" in line and "overreach" in line

    line = service._feel_vs_data_line(great, green)
    assert line.startswith("Feel vs data: aligned")

    assert service._feel_vs_data_line(None, green) is None
    assert service._feel_vs_data_line({"note": "hi"}, green) is None


def test_checkin_persists_and_story_reconciles(monkeypatch):
    monkeypatch.setattr(repo, "get_metrics", lambda: _metrics(400))
    with TestClient(app) as c:
        c.put("/api/athlete/profile", json={"ftp": 228, "threshold_hr": 160,
                                            "threshold_pace_run": 300, "weekly_hours_target": 7})
        c.post("/api/plan/generate?use_llm=false")
        r = c.post("/api/plan/checkin?use_llm=false",
                   json={"inputs": {"energy": 2, "sleep": 1, "body": 2, "stress": 2,
                                    "note": "brutal work week"}}).json()
        assert r["status"] == "ok"
        assert r["inputs"]["sleep"] == 1
        assert any(l.startswith("Feel vs data:") for l in r["story"])

        saved = repo.recent_checkins()
        assert len(saved) == 1
        assert saved[0]["inputs"]["note"] == "brutal work week"
        assert saved[0]["readiness"]["call"] == "hard"  # steady load
        assert any(l.startswith("Feel vs data:") for l in saved[0]["story"])

        # Second check-in: history now feeds the planner context.
        c.post("/api/plan/checkin?use_llm=false",
               json={"inputs": {"energy": 3, "sleep": 2, "body": 3, "stress": 3}})
        ctx = service._fitness_summary()
        assert len(ctx["recent_checkins"]) == 2
        assert ctx["recent_checkins"][0]["feel"]["sleep"] == 2  # newest first


def test_checkin_without_body_still_works(monkeypatch):
    monkeypatch.setattr(repo, "get_metrics", lambda: _metrics(400))
    with TestClient(app) as c:
        c.put("/api/athlete/profile", json={"ftp": 228, "weekly_hours_target": 7})
        c.post("/api/plan/generate?use_llm=false")
        r = c.post("/api/plan/checkin?use_llm=false").json()
        assert r["status"] == "ok"
        assert r["inputs"] is None
        assert not any(l.startswith("Feel vs data:") for l in r["story"])
        assert repo.recent_checkins()[0]["inputs"] is None


def test_async_checkin_carries_inputs(monkeypatch):
    monkeypatch.setattr(repo, "get_metrics", lambda: _metrics(400))
    with TestClient(app) as c:
        c.put("/api/athlete/profile", json={"ftp": 228, "weekly_hours_target": 7})
        c.post("/api/plan/generate?use_llm=false")
        job = c.post("/api/plan/checkin?use_llm=false&async=1",
                     json={"inputs": {"energy": 4}}).json()["job"]
        import time
        for _ in range(100):
            j = c.get(f"/api/jobs/{job['id']}").json()
            if j["status"] in ("succeeded", "failed"):
                break
            time.sleep(0.1)
        assert j["status"] == "succeeded"
        assert j["result"]["inputs"] == {"energy": 4}
