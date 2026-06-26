"""Pure functions that shape stored activity data into dashboard payloads:
weekly volume & compliance, per-sport trends, and a race-readiness projection.
"""

from __future__ import annotations

from collections import defaultdict
from datetime import date, datetime, timedelta

from .metrics import Thresholds

# Leg distances (meters) by race distance.
LEG_DISTANCES = {
    "70.3": {"swim": 1900, "bike": 90_000, "run": 21_100},
    "140.6": {"swim": 3860, "bike": 180_000, "run": 42_200},
}


def _day(s: str) -> date | None:
    try:
        return datetime.fromisoformat(str(s).replace("Z", "+00:00")).date()
    except (ValueError, TypeError):
        return None


def _week_start(d: date) -> date:
    return d - timedelta(days=d.weekday())  # Monday


# ── Weekly volume & compliance ────────────────────────────────────────────────


def weekly_volume(activities: list[dict], *, weeks: int = 16) -> list[dict]:
    """Actual training volume per ISO week, broken down by sport.

    `planned_*` fields are left at 0 here; the planner phase fills them in by
    joining planned_workouts so the dashboard can show planned-vs-actual.
    """
    buckets: dict[date, dict] = defaultdict(
        lambda: {"by_sport": defaultdict(lambda: {"hours": 0.0, "distance_km": 0.0, "tss": 0.0})}
    )
    for a in activities:
        d = _day(a.get("start_date"))
        if not d:
            continue
        ws = _week_start(d)
        sport = a.get("sport", "Other")
        s = buckets[ws]["by_sport"][sport]
        s["hours"] += (a.get("moving_time") or 0) / 3600.0
        s["distance_km"] += (a.get("distance") or 0) / 1000.0
        s["tss"] += a.get("tss") or 0.0

    out = []
    for ws in sorted(buckets):
        by_sport = {
            sp: {k: round(v, 1) for k, v in vals.items()}
            for sp, vals in buckets[ws]["by_sport"].items()
        }
        total_hours = round(sum(v["hours"] for v in by_sport.values()), 1)
        total_tss = round(sum(v["tss"] for v in by_sport.values()), 1)
        out.append(
            {
                "week_start": ws.isoformat(),
                "by_sport": by_sport,
                "total_hours": total_hours,
                "total_tss": total_tss,
            }
        )
    return out[-weeks:]


# ── Per-sport trends ──────────────────────────────────────────────────────────


def sport_trends(activities: list[dict]) -> dict:
    """Per-sport progression points for charting.

    Bike: weighted power + efficiency factor (power/HR).
    Run:  pace (sec/km) + efficiency factor (speed/HR).
    Swim: pace (sec/100m).
    """
    trends: dict[str, list[dict]] = {"Bike": [], "Run": [], "Swim": []}
    for a in activities:
        d = _day(a.get("start_date"))
        if not d:
            continue
        sport = a.get("sport")
        moving = a.get("moving_time") or 0
        distance = a.get("distance") or 0
        hr = a.get("avg_hr")
        iso = d.isoformat()
        if sport == "Bike":
            power = a.get("weighted_power") or a.get("avg_power")
            if power:
                trends["Bike"].append(
                    {
                        "date": iso,
                        "power": round(power, 0),
                        "hr": hr,
                        "ef": round(power / hr, 2) if hr else None,
                    }
                )
        elif sport == "Run" and distance > 0 and moving > 0:
            pace = moving / (distance / 1000.0)  # sec/km
            speed = distance / moving
            trends["Run"].append(
                {
                    "date": iso,
                    "pace": round(pace, 0),
                    "hr": hr,
                    "ef": round(speed * 100 / hr, 2) if hr else None,
                }
            )
        elif sport == "Swim" and distance > 0 and moving > 0:
            pace = moving / (distance / 100.0)  # sec/100m
            trends["Swim"].append({"date": iso, "pace": round(pace, 0), "hr": hr})
    return trends


# ── Race-readiness projection ─────────────────────────────────────────────────


def _recent_bike_speed(activities: list[dict], *, days: int = 84) -> float | None:
    """Average bike speed (m/s) on longer recent rides, as a race-pace proxy."""
    cutoff = date.today() - timedelta(days=days)
    speeds = []
    for a in activities:
        d = _day(a.get("start_date"))
        if not d or d < cutoff or a.get("sport") != "Bike":
            continue
        if (a.get("moving_time") or 0) >= 3600 and a.get("avg_speed"):
            speeds.append(a["avg_speed"])
    return sum(speeds) / len(speeds) if speeds else None


def _fmt_hms(seconds: float) -> str:
    seconds = int(seconds)
    h, rem = divmod(seconds, 3600)
    m, s = divmod(rem, 60)
    return f"{h}:{m:02d}:{s:02d}"


def race_readiness(
    activities: list[dict],
    th: Thresholds,
    *,
    current_ctl: float | None,
    cutoffs: dict | None = None,
    distance: str | None = None,
) -> dict:
    """Estimate race splits at current fitness and compare cumulative times to the
    official cut-offs. Leg distances follow the race (70.3 vs 140.6). Transparent,
    threshold-driven: swim ~ CSS*1.06, bike ~ recent long-ride speed, run ~
    threshold pace * 1.10.
    """
    legs = {}
    missing = []
    t1_s, t2_s = 5 * 60, 3 * 60  # transition estimates
    d = LEG_DISTANCES.get(str(distance), LEG_DISTANCES["70.3"])

    # Swim
    swim_s = None
    if th.css_swim:
        swim_pace = th.css_swim * 1.06  # sec/100m at race effort
        swim_s = d["swim"] / 100.0 * swim_pace
        legs["swim"] = {"seconds": round(swim_s), "display": _fmt_hms(swim_s)}
    else:
        missing.append("css_swim")

    # Bike — use historical long-ride speed as the practical proxy.
    bike_s = None
    bike_speed = _recent_bike_speed(activities)
    if bike_speed:
        bike_s = d["bike"] / bike_speed
        legs["bike"] = {"seconds": round(bike_s), "display": _fmt_hms(bike_s)}
    else:
        missing.append("bike_speed_history")

    # Run
    run_s = None
    if th.threshold_pace_run:
        run_pace = th.threshold_pace_run * 1.10  # sec/km off the bike
        run_s = d["run"] / 1000.0 * run_pace
        legs["run"] = {"seconds": round(run_s), "display": _fmt_hms(run_s)}
    else:
        missing.append("threshold_pace_run")

    transitions_s = t1_s + t2_s
    total_s = sum(leg["seconds"] for leg in legs.values()) + (transitions_s if legs else 0)

    return {
        "legs": legs,
        "transitions": {"seconds": transitions_s, "display": _fmt_hms(transitions_s)},
        "total": {"seconds": round(total_s), "display": _fmt_hms(total_s)} if legs else None,
        "current_ctl": round(current_ctl, 1) if current_ctl is not None else None,
        "missing": missing,
        "cutoffs": _cutoff_checks(swim_s, bike_s, run_s, t1_s, t2_s, cutoffs),
        "note": "Projection at current fitness from your thresholds and recent bike speed. "
        "Edit thresholds to refine.",
    }


def _cutoff_checks(
    swim_s: float | None,
    bike_s: float | None,
    run_s: float | None,
    t1_s: int,
    t2_s: int,
    cutoffs: dict | None,
) -> list[dict]:
    """Compare cumulative projected times to the 70.3 cut-offs (from start).

    Cut-offs are cumulative: swim = out of water; bike = swim+T1+bike; finish =
    everything. Margin > 0 means you're projected to make the cut-off.
    """
    c = cutoffs or {"swim": 70 * 60, "bike": 330 * 60, "finish": 510 * 60}
    checks = []

    def add(name: str, projected: float | None, limit: int):
        if projected is None:
            checks.append({"checkpoint": name, "limit_s": limit, "limit": _fmt_hms(limit),
                           "projected_s": None, "ok": None})
            return
        margin = limit - projected
        checks.append({
            "checkpoint": name,
            "limit_s": limit,
            "limit": _fmt_hms(limit),
            "projected_s": round(projected),
            "projected": _fmt_hms(projected),
            "margin_s": round(margin),
            "margin": ("-" if margin < 0 else "+") + _fmt_hms(abs(margin)),
            "ok": margin >= 0,
        })

    add("Swim", swim_s, c["swim"])
    cum_bike = (swim_s + t1_s + bike_s) if (swim_s is not None and bike_s is not None) else None
    add("Bike", cum_bike, c["bike"])
    cum_finish = (
        (swim_s + t1_s + bike_s + t2_s + run_s)
        if None not in (swim_s, bike_s, run_s)
        else None
    )
    add("Finish", cum_finish, c["finish"])
    return checks
