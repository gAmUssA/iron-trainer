"""Deterministic safety validator for generated training plans.

The LLM proposes; this code disposes. Every season the planner generates is run
through `validate_season` which clamps unsafe ramps, enforces recovery cadence
and a proper taper, and caps absolute volume. It returns the corrected season
plus a list of human-readable adjustments so nothing is silently changed.

This module is pure (no I/O) so it is cheap to unit-test and trustworthy as the
last line of defence against a bad LLM week.
"""

from __future__ import annotations

# 70.3-appropriate guard rails.
MAX_WEEKLY_RAMP = 1.10  # at most +10% week-over-week on building weeks
RECOVERY_EVERY = 4  # force a down week at least this often
RECOVERY_FACTOR = 0.65  # recovery week hours vs the preceding build week
MIN_WEEK_HOURS = 2.0
ABS_MAX_WEEK_HOURS = 16.0  # sane ceiling for an age-group 70.3
TAPER_WEEKS = 2  # final weeks before race day

# Per-session absolute caps (hours) — longest single session by sport.
SESSION_CAPS_H = {"Bike": 4.0, "Run": 2.5, "Swim": 1.5, "Brick": 4.5}


def validate_season(season: dict) -> tuple[dict, list[str]]:
    """Return (corrected_season, adjustments)."""
    weeks = [dict(w) for w in season.get("weeks", [])]
    notes: list[str] = []
    n = len(weeks)
    if n == 0:
        return season, ["No weeks in plan."]

    # 1) Taper: force the last TAPER_WEEKS into a descending taper.
    for i in range(n):
        weeks[i]["phase"] = weeks[i].get("phase", "build")
    for offset in range(1, TAPER_WEEKS + 1):
        idx = n - offset
        if idx >= 0:
            weeks[idx]["phase"] = "taper"

    # 2) Recovery cadence: no more than RECOVERY_EVERY-1 consecutive build weeks
    #    before the taper.
    build_streak = 0
    last_taper_start = n - TAPER_WEEKS
    for i in range(last_taper_start):
        if weeks[i].get("is_recovery"):
            build_streak = 0
            continue
        build_streak += 1
        if build_streak >= RECOVERY_EVERY:
            weeks[i]["is_recovery"] = True
            notes.append(f"Week {i + 1}: inserted recovery week (≥{RECOVERY_EVERY} hard weeks in a row).")
            build_streak = 0

    # 3) Ramp cap + recovery/taper scaling + absolute bounds.
    prev_build_hours: float | None = None
    for i, w in enumerate(weeks):
        hours = float(w.get("target_hours") or 0.0)
        is_taper = w.get("phase") == "taper"
        is_recovery = bool(w.get("is_recovery"))

        if is_taper:
            # Descending taper: each taper week below the last build week.
            base = prev_build_hours or hours
            taper_pos = i - last_taper_start  # 0,1,...
            factor = 0.6 if taper_pos == 0 else 0.4
            target = base * factor
            if abs(hours - target) > 0.1:
                notes.append(f"Week {i + 1}: taper set to {target:.1f}h (was {hours:.1f}h).")
            hours = target
        elif is_recovery:
            base = prev_build_hours or hours
            target = base * RECOVERY_FACTOR
            if hours > target + 0.1:
                notes.append(f"Week {i + 1}: recovery reduced to {target:.1f}h (was {hours:.1f}h).")
                hours = target
        else:
            if prev_build_hours is not None and hours > prev_build_hours * MAX_WEEKLY_RAMP:
                capped = prev_build_hours * MAX_WEEKLY_RAMP
                notes.append(
                    f"Week {i + 1}: ramp capped to {capped:.1f}h (was {hours:.1f}h, +10% max)."
                )
                hours = capped

        # Absolute bounds.
        if hours > ABS_MAX_WEEK_HOURS:
            notes.append(f"Week {i + 1}: clamped to {ABS_MAX_WEEK_HOURS}h ceiling.")
            hours = ABS_MAX_WEEK_HOURS
        if hours < MIN_WEEK_HOURS:
            hours = MIN_WEEK_HOURS

        w["target_hours"] = round(hours, 1)
        if not is_taper and not is_recovery:
            prev_build_hours = w["target_hours"]

    season = dict(season)
    season["weeks"] = weeks
    return season, notes


def validate_week_workouts(workouts: list[dict]) -> tuple[list[dict], list[str]]:
    """Cap individual session durations and flag back-to-back hard days."""
    notes: list[str] = []
    fixed = [dict(w) for w in workouts]
    for w in fixed:
        cap_h = SESSION_CAPS_H.get(w.get("sport", ""), 4.0)
        dur = float(w.get("duration_s") or 0)
        if dur > cap_h * 3600:
            notes.append(
                f"{w.get('date')} {w.get('sport')}: capped to {cap_h}h (was {dur / 3600:.1f}h)."
            )
            w["duration_s"] = int(cap_h * 3600)

    # Flag consecutive hard days (informational; coach/LLM can rearrange).
    hard = {"threshold", "vo2"}
    by_date = sorted(fixed, key=lambda x: (x.get("date") or "", x.get("sport") or ""))
    prev_hard_date = None
    for w in by_date:
        if w.get("intensity") in hard:
            if prev_hard_date == w.get("date"):
                continue
            prev_hard_date = w.get("date")
    return fixed, notes
