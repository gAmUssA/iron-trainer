"""Trend insights math: rolling means, regression verdicts, intensity buckets,
PRs, CTL trajectory, freshness."""

from datetime import date, timedelta

from app import insights as ins

TODAY = date(2026, 7, 6)


def _pts(values, key="power", start=TODAY - timedelta(days=83), step_days=7):
    return [
        {"date": (start + timedelta(days=i * step_days)).isoformat(), key: v}
        for i, v in enumerate(values)
    ]


def test_rolling_mean_trailing_window():
    pts = _pts([100, 200, 300, 400], step_days=7)  # 28-day window spans all 4
    roll = ins.rolling_mean(pts, "power")
    assert roll[0]["value"] == 100
    assert roll[-1]["value"] == 250  # mean of all four (span 21 days < 28)
    # With a 1-day window each point is its own mean.
    roll1 = ins.rolling_mean(pts, "power", window_days=1)
    assert [r["value"] for r in roll1] == [100, 200, 300, 400]


def test_slope_pct_direction_and_magnitude():
    up = ins.slope_pct(_pts([200, 210, 220, 230, 240]), "power", today=TODAY)
    assert up is not None and up > 15  # +40W on mean 220 ≈ +18%
    down = ins.slope_pct(_pts([240, 230, 220, 210, 200]), "power", today=TODAY)
    assert down is not None and down < -15
    flat = ins.slope_pct(_pts([220, 220, 220, 220, 220]), "power", today=TODAY)
    assert flat == 0
    assert ins.slope_pct(_pts([200, 210]), "power", today=TODAY) is None  # <4 points


def test_verdict_orientation_pace_vs_power():
    # Falling pace = improving; falling power = declining.
    trends = {
        "Bike": _pts([200, 195, 190, 185, 180], key="power"),
        "Run": _pts([320, 315, 310, 300, 295], key="pace"),
        "Swim": _pts([110, 108, 106, 104, 102], key="pace"),
    }
    out = ins.sport_insights(trends, today=TODAY)
    assert out["Bike"]["verdict"] == "declining"  # no EF data → falls back to power
    assert out["Bike"]["metric"] == "power"
    assert out["Run"]["verdict"] == "improving"
    assert out["Swim"]["verdict"] == "improving"


def test_bike_prefers_efficiency_factor_when_present():
    pts = [
        {**p, "ef": 1.30 + i * 0.02}  # EF rising while power flat
        for i, p in enumerate(_pts([200] * 5, key="power"))
    ]
    out = ins.sport_insights({"Bike": pts, "Run": [], "Swim": []}, today=TODAY)
    assert out["Bike"]["metric"] == "ef"
    assert out["Bike"]["verdict"] == "improving"


def test_intensity_mix_buckets_by_if():
    monday = ins._monday(TODAY)
    acts = [
        {"start_date": monday.isoformat(), "moving_time": 3600, "intensity_factor": 0.65},
        {"start_date": monday.isoformat(), "moving_time": 7200, "intensity_factor": 0.80},
        {"start_date": monday.isoformat(), "moving_time": 1800, "intensity_factor": 0.90},
        {"start_date": monday.isoformat(), "moving_time": 1800, "intensity_factor": 1.05},
        {"start_date": monday.isoformat(), "moving_time": 3600, "intensity_factor": None},
    ]
    weeks = ins.intensity_mix(acts, weeks=1, today=TODAY)
    assert len(weeks) == 1
    w = weeks[0]
    assert w["easy"] == 1.0 and w["endurance"] == 2.0
    assert w["tempo"] == 0.5 and w["hard"] == 0.5 and w["unknown"] == 1.0


def test_personal_records_floors():
    acts = [
        # 30-min ride at 400W must NOT take the ≥40-min power PR…
        {"start_date": "2026-05-01", "sport": "Bike", "moving_time": 1800,
         "weighted_power": 400, "distance": 20000, "name": "sprint"},
        # …this 60-min 250W ride does.
        {"start_date": "2026-05-02", "sport": "Bike", "moving_time": 3600,
         "weighted_power": 250, "distance": 30000, "name": "hour"},
        # 3k run can't take the 5k pace PR; 10k at 5:00/km does.
        {"start_date": "2026-05-03", "sport": "Run", "moving_time": 720,
         "distance": 3000, "name": "strides"},
        {"start_date": "2026-05-04", "sport": "Run", "moving_time": 3000,
         "distance": 10000, "name": "tempo 10k"},
    ]
    prs = ins.personal_records(acts)
    assert prs["bike_best_power_40min"]["value"] == 250
    assert prs["run_fastest_pace_5k"]["value"] == 300
    assert prs["longest_ride_m"]["value"] == 30000
    assert prs["longest_run_m"]["value"] == 10000
    assert prs["swim_fastest_pace_1k"] is None


def test_ctl_trajectory_projection():
    # CTL climbing 0.5/day for 60 days → ramp 3.5/wk.
    rows = [
        {"date": (TODAY - timedelta(days=60 - i)).isoformat(), "ctl": 30 + i * 0.5}
        for i in range(61)
    ]
    race = TODAY + timedelta(weeks=10)
    t = ins.ctl_trajectory(rows, race, today=TODAY)
    assert t["current"] == 60.0
    assert abs(t["ramp_per_week"] - 3.5) <= 0.1
    assert abs(t["race_day_projection"] - (60 + 3.5 * 10)) <= 1.5
    assert t["projection"][0]["date"] == TODAY.isoformat()
    assert t["projection"][-1]["date"] == race.isoformat()


def test_ctl_trajectory_no_race_or_data():
    assert ins.ctl_trajectory([], date(2026, 9, 26), today=TODAY) is None
    t = ins.ctl_trajectory([{"date": TODAY.isoformat(), "ctl": 50}], None, today=TODAY)
    assert t["current"] == 50 and "race_day_projection" not in t


def test_freshness():
    acts = [{"start_date": "2026-06-29T08:00:00"}, {"start_date": "2026-06-20"}]
    f = ins.freshness(acts, today=TODAY)
    assert f == {"last_activity": "2026-06-29", "days_stale": 7}
    assert ins.freshness([], today=TODAY)["days_stale"] is None


# ── Copilot follow-ups (PR #11) ───────────────────────────────────────────────


def test_rolling_mean_sliding_window_matches_naive():
    import random

    rng = random.Random(7)
    pts = []
    d = TODAY - timedelta(days=120)
    while d <= TODAY:
        if rng.random() < 0.6:
            pts.append({"date": d.isoformat(),
                        "power": rng.uniform(150, 300) if rng.random() < 0.9 else None})
        d += timedelta(days=1)

    def naive(points, key, window_days=28):
        out = []
        for p in points:
            dd = date.fromisoformat(p["date"])
            lo = dd - timedelta(days=window_days - 1)
            vals = [q[key] for q in points
                    if q.get(key) is not None and lo <= date.fromisoformat(q["date"]) <= dd]
            if vals:
                out.append({"date": p["date"], "value": round(sum(vals) / len(vals), 1)})
        return out

    assert ins.rolling_mean(pts, "power") == naive(pts, "power")


def test_steady_boundary_is_inclusive():
    assert ins._verdict(1.5, higher_is_better=True) == "steady"
    assert ins._verdict(-1.5, higher_is_better=False) == "steady"
    assert ins._verdict(1.6, higher_is_better=True) == "improving"


def test_ctl_trajectory_skips_unparseable_dates():
    rows = [{"date": "garbage", "ctl": 40},
            {"date": TODAY.isoformat(), "ctl": 50}]
    t = ins.ctl_trajectory(rows, None, today=TODAY)
    assert t is not None and t["current"] == 50
