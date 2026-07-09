"""Deterministic 70.3 plan structure.

Builds the season skeleton (phases, weekly volume ramp, recovery cadence) and
can expand any week into concrete structured workouts with power/pace/HR targets
derived from the athlete's thresholds. Used both as an offline fallback and as
the structural prior handed to the LLM so its output stays physiologically sane.
"""

from __future__ import annotations

from datetime import date, timedelta

from .. import zones

# Fraction of weekly volume per sport (typical 70.3 distribution).
SPORT_SPLIT = {"Swim": 0.20, "Bike": 0.50, "Run": 0.30}

# Intensity multipliers vs threshold, expressed as (low, high) ranges.
BIKE_PCT_FTP = {  # fraction of FTP
    "recovery": (0.50, 0.60),
    "endurance": (0.65, 0.75),
    "tempo": (0.76, 0.87),
    "threshold": (0.95, 1.05),
    "vo2": (1.10, 1.20),
}
RUN_PACE_FACTOR = {  # multiply threshold pace (sec/km); >1 = slower
    "recovery": (1.25, 1.35),
    "endurance": (1.15, 1.25),
    "tempo": (1.06, 1.10),
    "threshold": (0.99, 1.02),
    "vo2": (0.92, 0.96),
}
SWIM_PACE_FACTOR = {  # multiply CSS (sec/100m)
    "recovery": (1.12, 1.20),
    "endurance": (1.06, 1.12),
    "tempo": (1.02, 1.05),
    "threshold": (0.98, 1.02),
    "vo2": (0.94, 0.97),
}


def monday_of(d: date) -> date:
    return d - timedelta(days=d.weekday())


def build_season(*, start: date, race_date: date, weekly_hours: float) -> dict:
    """Create the week-by-week skeleton from `start` to race week."""
    weekly_hours = max(weekly_hours or 6.0, 4.0)
    first_monday = monday_of(start)
    race_monday = monday_of(race_date)
    n_weeks = max(int((race_monday - first_monday).days / 7) + 1, 4)

    weeks = []
    build_index = 0
    for i in range(n_weeks):
        ws = first_monday + timedelta(weeks=i)
        from_end = n_weeks - 1 - i
        is_taper = from_end < 2
        is_recovery = (i % 4 == 3) and not is_taper

        if is_taper:
            phase = "taper"
        elif from_end < 4:
            phase = "peak"
        elif i < n_weeks * 0.35:
            phase = "base"
        else:
            phase = "build"

        if is_taper or is_recovery:
            hours = weekly_hours  # validator scales these down
        else:
            hours = weekly_hours * (1.06**build_index)
            build_index += 1

        weeks.append(
            {
                "week_index": i + 1,
                "week_start": ws.isoformat(),
                "phase": phase,
                "is_recovery": is_recovery,
                "focus": _focus(phase, is_recovery),
                "target_hours": round(hours, 1),
                "target_tss": round(hours * 60, 0),
            }
        )

    return {
        "race_name": "IRONMAN 70.3",
        "race_date": race_date.isoformat(),
        "start_date": first_monday.isoformat(),
        "summary": (
            f"{n_weeks}-week 70.3 build: base → build → peak → 2-week taper, "
            "with recovery weeks every 4th week."
        ),
        "weeks": weeks,
    }


def _focus(phase: str, recovery: bool) -> str:
    if recovery:
        return "Recovery week — reduced volume, keep frequency, easy intensity."
    return {
        "base": "Aerobic base: long easy sessions, swim technique, build durability.",
        "build": "Add threshold work on bike/run; longer rides; open-water if possible.",
        "peak": "Race-specific: 70.3 effort bricks, biggest long ride, race nutrition.",
        "taper": "Sharpen and freshen: cut volume, keep short race-pace efforts.",
    }.get(phase, "")


# ── Week expansion into concrete workouts ─────────────────────────────────────


def expand_week(week: dict, profile: dict) -> list[dict]:
    """Turn one skeleton week into structured daily workouts."""
    ws = date.fromisoformat(week["week_start"])
    hours = float(week.get("target_hours") or 6.0)
    recovery = bool(week.get("is_recovery"))
    phase = week.get("phase", "build")

    swim_h = hours * SPORT_SPLIT["Swim"]
    bike_h = hours * SPORT_SPLIT["Bike"]
    run_h = hours * SPORT_SPLIT["Run"]

    hard = "endurance" if recovery else ("threshold" if phase in ("build", "peak") else "tempo")

    # day offsets: Mon..Sun = 0..6
    plan = [
        ("Swim", 1, swim_h * 0.45, "endurance", "Technique + aerobic swim"),
        ("Bike", 1, bike_h * 0.30, hard, "Bike intervals"),
        ("Run", 2, run_h * 0.35, hard, "Run quality session"),
        ("Swim", 3, swim_h * 0.55, "threshold" if not recovery else "endurance", "Swim CSS set"),
        ("Bike", 5, bike_h * 0.70, "endurance", "Long endurance ride"),
        ("Run", 6, run_h * 0.65, "endurance", "Long run"),
    ]

    workouts = []
    for sport, dow, h, intensity, title in plan:
        dur_s = int(max(h, 0.3) * 3600)
        wd = ws + timedelta(days=dow)
        steps, distance = _build_steps(sport, intensity, dur_s, profile)
        workouts.append(
            {
                "date": wd.isoformat(),
                "sport": sport,
                "title": title,
                "description": _describe(title, intensity, profile),
                "intensity": intensity,
                "duration_s": dur_s,
                "distance_m": distance,
                "planned_tss": round(dur_s / 3600 * _if_for(intensity) ** 2 * 100, 1),
                "steps": steps,
            }
        )
    return workouts


def _describe(title: str, intensity: str, profile: dict) -> str:
    """Workout description with the HR zone spelled out ("Z2 · HR 132–145 bpm")
    so zone guidance shows up in the web app, the iOS app, and every export."""
    zone = zones.zone_label(intensity)
    hr = zones.hr_range_for_intensity(intensity, profile.get("threshold_hr"), profile.get("max_hr"))
    if zone and hr:
        return f"{title} — {intensity} ({zone} · HR {hr[0]}–{hr[1]} bpm)."
    if zone:
        return f"{title} — {intensity} ({zone})."
    return f"{title} — {intensity}."


def _if_for(intensity: str) -> float:
    return {
        "recovery": 0.55,
        "endurance": 0.70,
        "tempo": 0.85,
        "threshold": 0.98,
        "vo2": 1.10,
    }.get(intensity, 0.70)


def _build_steps(sport: str, intensity: str, dur_s: int, profile: dict) -> tuple[list[dict], float | None]:
    """Warmup / main / cooldown with concrete targets from thresholds."""
    warm = int(dur_s * 0.15)
    cool = int(dur_s * 0.10)
    main = dur_s - warm - cool

    if sport == "Bike":
        ftp = profile.get("ftp")
        if ftp:
            target = _range_target("power", BIKE_PCT_FTP[intensity], base=ftp, unit="W")
            easy = _range_target("power", BIKE_PCT_FTP["endurance"], base=ftp, unit="W")
        else:
            # No power meter data — prescribe by heart-rate zone instead.
            target = _hr_target(intensity, profile)
            easy = _hr_target("endurance", profile)
        steps = [
            {"type": "warmup", "duration_s": warm, "target": easy},
            {"type": "steady", "duration_s": main, "target": target},
            {"type": "cooldown", "duration_s": cool, "target": easy},
        ]
        return steps, None

    if sport == "Run":
        thr = profile.get("threshold_pace_run")
        if thr:
            target = _range_target("pace", RUN_PACE_FACTOR[intensity], base=thr, unit="sec_per_km")
            easy = _range_target("pace", RUN_PACE_FACTOR["endurance"], base=thr, unit="sec_per_km")
        else:
            target = _hr_target(intensity, profile)
            easy = _hr_target("endurance", profile)
        steps = [
            {"type": "warmup", "duration_s": warm, "target": easy},
            {"type": "steady", "duration_s": main, "target": target},
            {"type": "cooldown", "duration_s": cool, "target": easy},
        ]
        distance = _est_distance_run(dur_s, thr, intensity)
        return steps, distance

    # Swim
    css = profile.get("css_swim")
    target = _range_target("pace", SWIM_PACE_FACTOR[intensity], base=css, unit="sec_per_100m")
    easy = _range_target("pace", SWIM_PACE_FACTOR["endurance"], base=css, unit="sec_per_100m")
    steps = [
        {"type": "warmup", "duration_s": warm, "target": easy},
        {"type": "steady", "duration_s": main, "target": target},
        {"type": "cooldown", "duration_s": cool, "target": easy},
    ]
    distance = _est_distance_swim(dur_s, css, intensity)
    return steps, distance


def _hr_target(intensity: str, profile: dict) -> dict:
    """HR-zone target for athletes without FTP / threshold pace."""
    hr = zones.hr_range_for_intensity(intensity, profile.get("threshold_hr"), profile.get("max_hr"))
    if hr is None:
        return {"type": "open", "unit": None, "low": None, "high": None}
    return {"type": "hr", "unit": "bpm", "low": hr[0], "high": hr[1]}


def _range_target(kind: str, factors: tuple[float, float], *, base: float | None, unit: str) -> dict:
    lo_f, hi_f = factors
    if base is None:
        return {"type": kind, "unit": unit, "low": None, "high": None}
    if unit == "W":
        return {"type": kind, "unit": unit, "low": round(base * lo_f), "high": round(base * hi_f)}
    # pace: higher factor = slower = larger sec value; keep low<high
    lo = round(base * lo_f)
    hi = round(base * hi_f)
    return {"type": kind, "unit": unit, "low": min(lo, hi), "high": max(lo, hi)}


def _est_distance_run(dur_s: int, thr_pace: float | None, intensity: str) -> float | None:
    if not thr_pace:
        return None
    mid = sum(RUN_PACE_FACTOR[intensity]) / 2
    pace = thr_pace * mid  # sec/km
    return round(dur_s / pace * 1000)


def _est_distance_swim(dur_s: int, css: float | None, intensity: str) -> float | None:
    if not css:
        return None
    mid = sum(SWIM_PACE_FACTOR[intensity]) / 2
    pace = css * mid  # sec/100m
    return round(dur_s / pace * 100)
