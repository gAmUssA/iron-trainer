"""Daily readiness call: ACWR math, modifiers, endpoint, and check-in story line."""

from datetime import date, timedelta

from app import readiness

TODAY = date(2026, 7, 14)


def _rows(daily: dict[int, float], *, tsb: float = 0.0, days: int = 42) -> list[dict]:
    """Build a metrics series ending yesterday. `daily` maps days-ago -> TSS."""
    out = []
    for ago in range(days, 0, -1):
        d = TODAY - timedelta(days=ago)
        out.append({
            "date": d.isoformat(),
            "tss": daily.get(ago, 0.0),
            "ctl": 50.0,
            "atl": 50.0,
            "tsb": tsb,
        })
    return out


def _flat(week_tss: float, *, tsb: float = 0.0, days: int = 42) -> list[dict]:
    per_day = week_tss / 7.0
    return _rows({ago: per_day for ago in range(1, days + 1)}, tsb=tsb, days=days)


def test_insufficient_history():
    out = readiness.compute(_flat(400, days=7), today=TODAY)
    assert out["status"] == "insufficient_data"
    assert out["call"] is None
    assert readiness.story_line(out) is None


def test_insufficient_chronic_load():
    out = readiness.compute(_flat(10), today=TODAY)
    assert out["status"] == "insufficient_data"


def test_steady_load_is_green_hard():
    out = readiness.compute(_flat(400), today=TODAY)
    assert (out["status"], out["call"], out["level"]) == ("ok", "hard", "green")
    assert out["acwr"] == 1.0
    assert "ratio 1.00" in out["reasons"][0]


def test_acwr_spike_is_red_rest():
    # 4-week norm ~400/wk, but the last 7 days doubled it.
    daily = {ago: 400 / 7 for ago in range(1, 29)}
    for ago in range(1, 8):
        daily[ago] = 800 / 7
    out = readiness.compute(_rows(daily), today=TODAY)
    # acute=800, chronic=(800+3*400)/4=500 -> 1.6
    assert (out["call"], out["level"]) == ("rest", "red")
    assert out["acwr"] == 1.6


def test_acwr_elevated_is_amber_easy():
    daily = {ago: 400 / 7 for ago in range(1, 29)}
    for ago in range(1, 8):
        daily[ago] = 560 / 7  # acute 560, chronic (560+1200)/4=440 -> ~1.27… bump a bit
    for ago in range(1, 8):
        daily[ago] = 600 / 7  # acute 600, chronic 450 -> 1.33
    out = readiness.compute(_rows(daily), today=TODAY)
    assert (out["call"], out["level"]) == ("easy", "amber")
    assert 1.3 < out["acwr"] <= 1.5


def test_deep_fatigue_overrides_steady_ratio():
    out = readiness.compute(_flat(400, tsb=-30), today=TODAY)
    assert (out["call"], out["level"]) == ("easy", "amber")
    assert "TSB" in out["reasons"][0]


def test_back_to_back_hard_days_amber():
    # Steady 400/wk (~57/day) but yesterday and the day before were ~120 TSS.
    daily = {ago: 400 / 7 for ago in range(1, 43)}
    daily[1] = daily[2] = 120.0
    # Keep acute under the 1.3 band: 2*120 + 5*57.1 = 525.7; chronic ~ (525.7+3*400)/4 = 431 -> 1.22
    out = readiness.compute(_rows(daily), today=TODAY)
    assert out["acwr"] < 1.3
    assert (out["call"], out["level"]) == ("easy", "amber")
    assert out["hard_day_streak"] == 2


def test_fresh_and_underloaded_notes_key_session():
    daily = {ago: 400 / 7 for ago in range(8, 43)}  # nothing in the last 7 days
    for ago in range(1, 8):
        daily[ago] = 200 / 7
    out = readiness.compute(_rows(daily, tsb=15), today=TODAY)
    assert (out["call"], out["level"]) == ("hard", "green")
    assert out["acwr"] < 0.8
    assert "key session" in out["reasons"][0]


def test_stale_tsb_cannot_veto_the_call():
    """A metrics series that stopped updating weeks ago (lapsed sync) must not
    produce a phantom deep-fatigue call off its last stored TSB."""
    daily = {ago: 400 / 7 for ago in range(19, 43)}
    rows = _rows(daily, tsb=-30)
    cutoff = (TODAY - timedelta(days=18)).isoformat()
    rows = [r for r in rows if r["date"] < cutoff]  # series ends 18 days ago
    out = readiness.compute(rows, today=TODAY)
    assert out["status"] == "ok"
    assert out["call"] == "hard"  # acwr ~0 and TSB too stale to trust


def test_today_excluded_from_window():
    rows = _flat(400)
    rows.append({"date": TODAY.isoformat(), "tss": 500.0, "ctl": 50, "atl": 50, "tsb": 0})
    out = readiness.compute(rows, today=TODAY)
    assert out["acwr"] == 1.0  # this morning's logged ride doesn't flip today's call


def test_story_line_formats_call():
    out = readiness.compute(_flat(400), today=TODAY)
    line = readiness.story_line(out)
    assert line.startswith("Today's call: HARD — ")


def test_checkin_story_includes_readiness_call(monkeypatch):
    """With real metric history, the weekly check-in narrates today's call and
    exposes the structured readiness payload for clients."""
    from starlette.testclient import TestClient

    from app import repo
    from app.main import app

    rows = _flat(400, days=42)
    # Re-anchor the series to end yesterday relative to the real today.
    offset = date.today() - TODAY
    for r in rows:
        r["date"] = (date.fromisoformat(r["date"]) + offset).isoformat()
    monkeypatch.setattr(repo, "get_metrics", lambda: rows)

    with TestClient(app) as c:
        c.put("/api/athlete/profile", json={"ftp": 228, "threshold_hr": 160,
                                            "threshold_pace_run": 300, "weekly_hours_target": 7})
        c.post("/api/plan/generate?use_llm=false")
        r = c.post("/api/plan/checkin?use_llm=false").json()
        assert r["status"] == "ok"
        assert r["readiness"]["status"] == "ok"
        assert r["readiness"]["call"] == "hard"
        assert any(line.startswith("Today's call: HARD") for line in r["story"])


def test_endpoint_returns_readiness():
    from starlette.testclient import TestClient

    from app.main import app

    with TestClient(app) as c:
        r = c.get("/api/metrics/readiness/today")
        assert r.status_code == 200
        body = r.json()
        assert body["status"] in ("ok", "insufficient_data")
        assert "call" in body and "reasons" in body
