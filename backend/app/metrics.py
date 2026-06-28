"""Training-load math: TSS per activity and CTL/ATL/TSB time series.

All formulas converge on the standard TrainingPeaks relationship:

    TSS = hours * IF^2 * 100

where the Intensity Factor (IF) is the ratio of the effort's intensity to the
athlete's threshold for that sport. We derive IF from the best signal available
on each activity, in priority order: power -> pace -> heart rate -> duration.
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from datetime import date, timedelta

# Fallback IF when no intensity signal is available (treat as moderate aerobic).
DEFAULT_IF = 0.70


def normalized_power(samples: list[float | None], dt_s: float = 1.0) -> float | None:
    """Normalized Power from a ~1 Hz power stream: 30 s rolling average, raised to the
    4th power, averaged, 4th root. Returns None without enough data."""
    from collections import deque

    vals = [float(p) for p in samples if p is not None]
    window = max(1, round(30.0 / dt_s))
    if len(vals) < max(30, window):
        return None
    win: deque[float] = deque(maxlen=window)
    rolled: list[float] = []
    for p in vals:
        win.append(p)
        if len(win) == window:
            rolled.append((sum(win) / window) ** 4)
    if not rolled:
        return None
    return round((sum(rolled) / len(rolled)) ** 0.25)


CTL_TIME_CONSTANT = 42  # days (chronic / "fitness")
ATL_TIME_CONSTANT = 7  # days (acute / "fatigue")


@dataclass
class Thresholds:
    ftp: float | None = None  # watts
    threshold_hr: int | None = None  # bpm
    max_hr: int | None = None  # bpm
    threshold_pace_run: float | None = None  # sec / km
    css_swim: float | None = None  # sec / 100m


# ── Sport normalisation ───────────────────────────────────────────────────────

_SPORT_MAP = {
    "Run": "Run",
    "TrailRun": "Run",
    "VirtualRun": "Run",
    "Ride": "Bike",
    "VirtualRide": "Bike",
    "GravelRide": "Bike",
    "MountainBikeRide": "Bike",
    "EBikeRide": "Bike",
    "Swim": "Swim",
}


def normalize_sport(strava_type: str | None) -> str:
    return _SPORT_MAP.get(strava_type or "", "Other")


# ── Intensity Factor per sport ────────────────────────────────────────────────


def _clamp_if(value: float) -> float:
    # Guard against absurd values from noisy summary data.
    return max(0.3, min(value, 1.25))


def intensity_factor(
    sport: str,
    *,
    moving_time: float | None,
    distance: float | None,
    weighted_power: float | None,
    avg_power: float | None,
    avg_hr: float | None,
    th: Thresholds,
) -> tuple[float, str]:
    """Return (IF, method) for one activity using the best available signal."""
    # 1) Power (bike): IF = NP / FTP
    if sport == "Bike" and th.ftp:
        power = weighted_power or avg_power
        if power:
            return _clamp_if(power / th.ftp), "power"

    # 2) Pace (run): IF = threshold_pace_speed_ratio = thresh_pace / actual_pace
    if sport == "Run" and th.threshold_pace_run and moving_time and distance:
        km = distance / 1000.0
        if km > 0:
            actual_pace = moving_time / km  # sec/km
            if actual_pace > 0:
                return _clamp_if(th.threshold_pace_run / actual_pace), "pace"

    # 3) Pace (swim): IF = css_pace / actual_pace_per_100
    if sport == "Swim" and th.css_swim and moving_time and distance:
        hundreds = distance / 100.0
        if hundreds > 0:
            actual_pace = moving_time / hundreds  # sec/100m
            if actual_pace > 0:
                return _clamp_if(th.css_swim / actual_pace), "pace"

    # 4) Heart rate: IF ~= avg_hr / threshold_hr
    if avg_hr and th.threshold_hr:
        return _clamp_if(avg_hr / th.threshold_hr), "hr"

    # 5) Duration-only fallback.
    return DEFAULT_IF, "duration"


def compute_tss(
    sport: str,
    *,
    moving_time: float | None,
    distance: float | None,
    weighted_power: float | None,
    avg_power: float | None,
    avg_hr: float | None,
    th: Thresholds,
) -> tuple[float, float, str]:
    """Return (tss, intensity_factor, method) for one activity."""
    if not moving_time or moving_time <= 0:
        return 0.0, 0.0, "none"
    if_value, method = intensity_factor(
        sport,
        moving_time=moving_time,
        distance=distance,
        weighted_power=weighted_power,
        avg_power=avg_power,
        avg_hr=avg_hr,
        th=th,
    )
    hours = moving_time / 3600.0
    tss = hours * (if_value**2) * 100.0
    return round(tss, 1), round(if_value, 3), method


# ── CTL / ATL / TSB time series ───────────────────────────────────────────────


@dataclass
class DayMetric:
    day: date
    tss: float
    ctl: float
    atl: float
    tsb: float


def daily_tss(activities: list[tuple[date, float]]) -> dict[date, float]:
    """Sum TSS by calendar day from (date, tss) pairs."""
    totals: dict[date, float] = defaultdict(float)
    for day, tss in activities:
        totals[day] += tss or 0.0
    return totals


def performance_management(
    activities: list[tuple[date, float]],
    *,
    end: date,
    start: date | None = None,
    ctl_seed: float = 0.0,
    atl_seed: float = 0.0,
) -> list[DayMetric]:
    """Compute CTL/ATL/TSB for every day from start..end.

    TSB (form) on a given day is yesterday's CTL minus yesterday's ATL — the
    standard TrainingPeaks convention so that today's training doesn't make you
    look instantly more fatigued.
    """
    totals = daily_tss(activities)
    if not totals and start is None:
        return []
    first = start or min(totals)
    if first > end:
        return []

    ctl_alpha = 1.0 / CTL_TIME_CONSTANT
    atl_alpha = 1.0 / ATL_TIME_CONSTANT

    out: list[DayMetric] = []
    ctl, atl = ctl_seed, atl_seed
    day = first
    while day <= end:
        prev_ctl, prev_atl = ctl, atl
        tss = totals.get(day, 0.0)
        ctl = prev_ctl + (tss - prev_ctl) * ctl_alpha
        atl = prev_atl + (tss - prev_atl) * atl_alpha
        tsb = prev_ctl - prev_atl  # form uses yesterday's values
        out.append(
            DayMetric(day=day, tss=round(tss, 1), ctl=round(ctl, 1), atl=round(atl, 1), tsb=round(tsb, 1))
        )
        day += timedelta(days=1)
    return out
