"""Trend insights: rolling averages, regression verdicts, intensity mix, PRs and
the CTL race-day trajectory. Pure functions over stored activity/metric summaries
(no I/O), same design as dashboards.py — cheap to unit-test.

Everything here is derivable from per-activity SUMMARY data (we don't store
streams): efficiency factor = output per heartbeat (bike: power/HR, run:
speed·100/HR), intensity buckets from the stored intensity_factor, CTL from the
daily metrics table.
"""

from __future__ import annotations

from collections import defaultdict
from datetime import date, timedelta

ROLLING_DAYS = 28  # window for the rolling mean the trendline draws
SLOPE_DAYS = 84  # regression window for the improving/declining verdict
STEADY_PCT = 1.5  # |change| below this over the window reads as "steady"

# Weekly intensity buckets from the stored IF (session-average intensity).
IF_BUCKETS = [
    ("easy", 0.0, 0.70),
    ("endurance", 0.70, 0.85),
    ("tempo", 0.85, 0.95),
    ("hard", 0.95, 10.0),
]


def _day(v) -> date | None:
    if not v:
        return None
    try:
        return date.fromisoformat(str(v)[:10])
    except ValueError:
        return None


def _monday(d: date) -> date:
    return d - timedelta(days=d.weekday())


def rolling_mean(points: list[dict], key: str, window_days: int = ROLLING_DAYS) -> list[dict]:
    """Trailing mean over the previous `window_days` for each point (dates ISO).

    Single-pass sliding window (points arrive date-ordered from sport_trends),
    so a multi-year history doesn't turn this O(n²)."""
    parsed = [
        (date.fromisoformat(p["date"]), p["date"], p.get(key))
        for p in points
    ]
    out: list[dict] = []
    window: list[tuple[date, float]] = []  # (date, value) inside the current window
    total = 0.0
    start = 0  # window head index into `window`
    for d, iso, val in parsed:
        if val is not None:
            window.append((d, float(val)))
            total += float(val)
        lo = d - timedelta(days=window_days - 1)
        while start < len(window) and window[start][0] < lo:
            total -= window[start][1]
            start += 1
        n = len(window) - start
        if n > 0:
            out.append({"date": iso, "value": round(total / n, 1)})
    return out


def slope_pct(points: list[dict], key: str, days: int = SLOPE_DAYS,
              today: date | None = None) -> float | None:
    """Least-squares % change of `key` across the last `days` days.

    Returned as (slope × span) / mean × 100 — "the metric moved X% over the
    window". None with fewer than 4 points (a line through noise isn't a trend).
    """
    today = today or date.today()
    lo = today - timedelta(days=days)
    xs: list[float] = []
    ys: list[float] = []
    for p in points:
        d = date.fromisoformat(p["date"])
        if d >= lo and p.get(key) is not None:
            xs.append((d - lo).days)
            ys.append(float(p[key]))
    if len(xs) < 4:
        return None
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    denom = sum((x - mx) ** 2 for x in xs)
    if denom == 0 or my == 0:
        return None
    slope = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / denom
    span = max(xs) - min(xs) or 1
    return round(slope * span / my * 100, 1)


def _verdict(change_pct: float | None, *, higher_is_better: bool) -> str:
    if change_pct is None:
        return "insufficient data"
    if abs(change_pct) <= STEADY_PCT:  # ±1.5% inclusive reads as steady
        return "steady"
    improving = change_pct > 0 if higher_is_better else change_pct < 0
    return "improving" if improving else "declining"


def sport_insights(trends: dict, today: date | None = None) -> dict:
    """Per-sport rolling trendline + regression verdict.

    Bike verdict prefers efficiency factor (power per heartbeat — fitness signal
    independent of how hard you rode); falls back to raw power. Run: EF first,
    else pace. Swim: pace.
    """
    out: dict = {}
    spec = {
        # sport: (primary key, higher_is_better, fallback key, fallback higher_is_better)
        "Bike": ("ef", True, "power", True),
        "Run": ("ef", True, "pace", False),
        "Swim": ("pace", False, None, None),
    }
    value_key = {"Bike": "power", "Run": "pace", "Swim": "pace"}
    for sport, (pk, phib, fk, fhib) in spec.items():
        pts = trends.get(sport, [])
        key, hib = pk, phib
        if fk is not None and sum(1 for p in pts if p.get(pk) is not None) < 4:
            key, hib = fk, fhib
        change = slope_pct(pts, key, today=today)
        out[sport] = {
            "metric": key,
            "change_pct": change,
            "verdict": _verdict(change, higher_is_better=hib),
            "rolling": rolling_mean(pts, value_key[sport]),
            "rolling_ef": rolling_mean(pts, "ef") if sport != "Swim" else [],
        }
    return out


def intensity_mix(activities: list[dict], weeks: int = 12,
                  today: date | None = None) -> list[dict]:
    """Weekly hours per IF bucket — shows whether training is polarized or a
    grey-zone mush. Sessions without an IF land in 'unknown'."""
    today = today or date.today()
    start = _monday(today) - timedelta(weeks=weeks - 1)
    rows: dict[date, dict] = defaultdict(lambda: {b[0]: 0.0 for b in IF_BUCKETS} | {"unknown": 0.0})
    for a in activities:
        d = _day(a.get("start_date"))
        if not d or d < start:
            continue
        hours = (a.get("moving_time") or 0) / 3600.0
        if hours <= 0:
            continue
        if_v = a.get("intensity_factor")
        bucket = "unknown"
        # IF 0.0 means compute_tss had nothing to grade the session on (the
        # "none" method) — that's unknown intensity, deliberately not "easy".
        if if_v:
            for name, lo, hi in IF_BUCKETS:
                if lo <= if_v < hi:
                    bucket = name
                    break
        rows[_monday(d)][bucket] += hours
    out = []
    wk = start
    while wk <= _monday(today):
        r = rows.get(wk, {b[0]: 0.0 for b in IF_BUCKETS} | {"unknown": 0.0})
        out.append({"week_start": wk.isoformat(), **{k: round(v, 2) for k, v in r.items()}})
        wk += timedelta(weeks=1)
    return out


def personal_records(activities: list[dict]) -> dict:
    """Bests derivable from summaries. Duration/distance floors keep sprints and
    GPS blips from claiming records."""
    best_power = fastest_run = fastest_swim = None
    longest_ride = longest_run = None
    for a in activities:
        d = _day(a.get("start_date"))
        if not d:
            continue
        sport = a.get("sport")
        moving = a.get("moving_time") or 0
        dist = a.get("distance") or 0
        ref = {"date": d.isoformat(), "name": a.get("name")}
        if sport == "Bike":
            power = a.get("weighted_power") or a.get("avg_power")
            if power and moving >= 2400 and (not best_power or power > best_power["value"]):
                best_power = {**ref, "value": round(power)}
            if dist and (not longest_ride or dist > longest_ride["value"]):
                longest_ride = {**ref, "value": round(dist)}
        elif sport == "Run" and dist > 0 and moving > 0:
            pace = moving / (dist / 1000.0)
            if dist >= 5000 and (not fastest_run or pace < fastest_run["value"]):
                fastest_run = {**ref, "value": round(pace)}
            if not longest_run or dist > longest_run["value"]:
                longest_run = {**ref, "value": round(dist)}
        elif sport == "Swim" and dist >= 1000 and moving > 0:
            pace = moving / (dist / 100.0)
            if not fastest_swim or pace < fastest_swim["value"]:
                fastest_swim = {**ref, "value": round(pace)}
    return {
        "bike_best_power_40min": best_power,  # W, rides ≥ 40 min
        "run_fastest_pace_5k": fastest_run,  # sec/km, runs ≥ 5 km
        "swim_fastest_pace_1k": fastest_swim,  # sec/100m, swims ≥ 1 km
        "longest_ride_m": longest_ride,
        "longest_run_m": longest_run,
    }


def ctl_trajectory(metrics: list[dict], race_date: date | None,
                   today: date | None = None) -> dict | None:
    """Current CTL, the 4-week ramp rate, and a straight-line projection to race
    day at the current ramp. A projection, not a promise — but it answers 'am I
    building fast enough?' at a glance."""
    if not metrics:
        return None
    today = today or date.today()
    rows = [m for m in metrics if m.get("ctl") is not None and _day(m.get("date"))]
    if not rows:
        return None
    current = float(rows[-1]["ctl"])
    d28 = _day(rows[-1]["date"]) - timedelta(days=28)
    past = [m for m in rows if _day(m["date"]) <= d28]
    ramp_per_week = round((current - float(past[-1]["ctl"])) / 4.0, 1) if past else None

    out: dict = {"current": round(current, 1), "ramp_per_week": ramp_per_week}
    if race_date and race_date > today and ramp_per_week is not None:
        weeks_left = (race_date - today).days / 7.0
        out["race_date"] = race_date.isoformat()
        out["weeks_to_race"] = round(weeks_left, 1)
        out["race_day_projection"] = round(current + ramp_per_week * weeks_left, 1)
        # Weekly projection points so the chart can draw the dashed line.
        pts = []
        d = today
        while d <= race_date:
            w = (d - today).days / 7.0
            pts.append({"date": d.isoformat(), "ctl": round(current + ramp_per_week * w, 1)})
            d += timedelta(weeks=1)
        if pts and pts[-1]["date"] != race_date.isoformat():
            pts.append({"date": race_date.isoformat(), "ctl": out["race_day_projection"]})
        out["projection"] = pts
    return out


def freshness(activities: list[dict], today: date | None = None) -> dict:
    """When the data was last fed — the difference between 'no progress' and
    'you just haven't synced'."""
    today = today or date.today()
    days = [d for a in activities if (d := _day(a.get("start_date")))]
    if not days:
        return {"last_activity": None, "days_stale": None}
    last = max(days)
    return {"last_activity": last.isoformat(), "days_stale": (today - last).days}


def build(activities: list[dict], trends: dict, metrics: list[dict],
          race_date: date | None, today: date | None = None) -> dict:
    """Everything the Trends page needs beyond the raw per-activity points."""
    return {
        "sports": sport_insights(trends, today=today),
        "intensity_weeks": intensity_mix(activities, today=today),
        "prs": personal_records(activities),
        "ctl_trajectory": ctl_trajectory(metrics, race_date, today=today),
        "freshness": freshness(activities, today=today),
    }
