"""Infer athlete thresholds and weekly availability from recent Strava history.

These are deliberately simple, transparent heuristics meant to *seed* the
athlete profile from summary activity data (we don't pull per-second streams).
Every value is surfaced in the UI for the athlete to confirm or override, and
all of them feed TSS/zone math downstream.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import date, datetime, timedelta


@dataclass
class InferredProfile:
    ftp: float | None = None
    threshold_hr: int | None = None
    max_hr: int | None = None
    threshold_pace_run: float | None = None  # sec/km
    css_swim: float | None = None  # sec/100m
    weekly_hours_target: float | None = None
    basis: dict | None = None  # human-readable explanation of each value

    def as_dict(self) -> dict:
        return asdict(self)


def _parse_day(s: str) -> date:
    return datetime.fromisoformat(s.replace("Z", "+00:00")).date()


def infer_profile(
    activities: list[dict],
    *,
    today: date,
    threshold_window_days: int = 84,
    availability_window_days: int = 56,
) -> InferredProfile:
    """activities: dicts with keys sport, start_date, moving_time, distance,
    weighted_power, avg_power, avg_hr, max_hr."""
    th_cutoff = today - timedelta(days=threshold_window_days)
    av_cutoff = today - timedelta(days=availability_window_days)

    best_np = 0.0  # bike normalized power, sustained efforts
    fastest_run_pace: float | None = None  # sec/km (smaller is faster)
    fastest_swim_pace: float | None = None  # sec/100m
    best_threshold_hr = 0
    observed_max_hr = 0
    availability_seconds = 0.0
    basis: dict = {}

    for a in activities:
        try:
            day = _parse_day(a["start_date"])
        except (KeyError, ValueError):
            continue
        sport = a.get("sport")
        moving = a.get("moving_time") or 0
        distance = a.get("distance") or 0
        avg_hr = a.get("avg_hr")
        max_hr = a.get("max_hr")

        if max_hr:
            observed_max_hr = max(observed_max_hr, int(max_hr))

        # Availability: total moving time across all sports in recent window.
        if day >= av_cutoff:
            availability_seconds += moving

        if day < th_cutoff:
            continue

        sustained = moving >= 1200  # >= 20 minutes

        if sport == "Bike" and sustained:
            np = a.get("weighted_power") or a.get("avg_power")
            if np:
                best_np = max(best_np, float(np))
            if avg_hr:
                best_threshold_hr = max(best_threshold_hr, int(avg_hr))

        if sport == "Run" and sustained and distance > 0:
            pace = moving / (distance / 1000.0)  # sec/km
            if fastest_run_pace is None or pace < fastest_run_pace:
                fastest_run_pace = pace
            if avg_hr:
                best_threshold_hr = max(best_threshold_hr, int(avg_hr))

        if sport == "Swim" and distance >= 400:
            pace = moving / (distance / 100.0)  # sec/100m
            if fastest_swim_pace is None or pace < fastest_swim_pace:
                fastest_swim_pace = pace

    profile = InferredProfile()

    if best_np > 0:
        profile.ftp = round(best_np * 0.95, 0)  # ~95% of best sustained NP
        basis["ftp"] = f"95% of best sustained bike NP ({best_np:.0f}W) in last 12 weeks"

    if fastest_run_pace:
        # True threshold pace is slightly slower than a single best hard effort.
        profile.threshold_pace_run = round(fastest_run_pace * 1.04, 0)
        basis["threshold_pace_run"] = (
            f"fastest sustained run pace ({_fmt_pace_km(fastest_run_pace)}) +4%"
        )

    if fastest_swim_pace:
        profile.css_swim = round(fastest_swim_pace, 0)
        basis["css_swim"] = f"fastest sustained swim pace ({_fmt_pace_100(fastest_swim_pace)})"

    if best_threshold_hr:
        profile.threshold_hr = best_threshold_hr
        basis["threshold_hr"] = (
            f"max avg HR on sustained efforts ({best_threshold_hr} bpm)"
        )
    elif observed_max_hr:
        profile.threshold_hr = round(observed_max_hr * 0.92)
        basis["threshold_hr"] = f"~92% of observed max HR ({observed_max_hr} bpm)"

    if observed_max_hr:
        profile.max_hr = observed_max_hr

    weeks = availability_window_days / 7.0
    hours = availability_seconds / 3600.0
    if hours > 0:
        profile.weekly_hours_target = round(hours / weeks, 1)
        basis["weekly_hours_target"] = (
            f"avg of {hours:.1f}h over last {int(weeks)} weeks"
        )

    profile.basis = basis
    return profile


def _fmt_pace_km(sec_per_km: float) -> str:
    m, s = divmod(int(sec_per_km), 60)
    return f"{m}:{s:02d}/km"


def _fmt_pace_100(sec_per_100: float) -> str:
    m, s = divmod(int(sec_per_100), 60)
    return f"{m}:{s:02d}/100m"
