"""Detect duplicate activities — the same workout captured by more than one
device (e.g. an Apple Watch and a Garmin bike computer both recording one ride).

Pure functions: cluster overlapping same-sport activities, then pick which one
to keep. Apple Watch is preferred per the athlete's request, then richer data
(HR, power), then the longest recording.
"""

from __future__ import annotations

from datetime import datetime

# Two activities are considered the same event if same sport, they start within
# this window, and their durations are broadly similar.
START_TOLERANCE_S = 20 * 60
DURATION_RATIO_MIN = 0.5


def _start(a: dict) -> datetime | None:
    try:
        return datetime.fromisoformat(str(a["start_date"]).replace("Z", "+00:00"))
    except (KeyError, ValueError, TypeError):
        return None


def is_apple_watch(device_name: str | None) -> bool:
    return bool(device_name) and "apple watch" in device_name.lower()


# Head units that record the ride with power/cadence — preferred for cycling.
_BIKE_COMPUTERS = ("edge", "elemnt", "wahoo", "bolt", "roam", "karoo", "hammerhead")


def is_bike_computer(device_name: str | None) -> bool:
    return bool(device_name) and any(k in device_name.lower() for k in _BIKE_COMPUTERS)


def _same_event(a: dict, b: dict) -> bool:
    if a.get("sport") != b.get("sport") or a.get("sport") == "Other":
        return False
    sa, sb = _start(a), _start(b)
    if not sa or not sb:
        return False
    # Compare on naive timestamps to avoid tz mismatch between sources.
    gap = abs((sa.replace(tzinfo=None) - sb.replace(tzinfo=None)).total_seconds())
    if gap > START_TOLERANCE_S:
        return False
    da, db = a.get("moving_time") or 0, b.get("moving_time") or 0
    if da <= 0 or db <= 0:
        return gap <= 120  # fall back to tight start match
    ratio = min(da, db) / max(da, db)
    return ratio >= DURATION_RATIO_MIN


def cluster_duplicates(activities: list[dict]) -> list[list[dict]]:
    """Return groups (size >= 2) of activities that represent the same event."""
    ordered = sorted(activities, key=lambda a: str(a.get("start_date") or ""))
    clusters: list[list[dict]] = []
    for act in ordered:
        placed = False
        for cluster in clusters:
            if any(_same_event(act, member) for member in cluster):
                cluster.append(act)
                placed = True
                break
        if not placed:
            clusters.append([act])
    return [c for c in clusters if len(c) > 1]


def _has_power(a: dict) -> int:
    return 1 if (a.get("has_power_meter") or a.get("weighted_power")) else 0


def _has_hr(a: dict) -> int:
    return 1 if a.get("avg_hr") else 0


def _score(a: dict, sport: str | None) -> tuple:
    """Higher is better. Source preference is sport-aware:

    * Cycling — prefer the bike computer (power/cadence from the Garmin Edge +
      power meter), then power data, then HR, then the longest recording.
    * Swim / Run / other — prefer the Apple Watch, then HR, then power, length.
    """
    length = (a.get("moving_time") or 0, a.get("distance") or 0)
    if sport == "Bike":
        return (
            1 if is_bike_computer(a.get("device_name")) else 0,
            _has_power(a),
            _has_hr(a),
            *length,
        )
    return (
        1 if is_apple_watch(a.get("device_name")) else 0,
        _has_hr(a),
        _has_power(a),
        *length,
    )


def primary_of(cluster: list[dict]) -> dict:
    """Choose the activity to keep from a duplicate cluster (sport-aware)."""
    sport = cluster[0].get("sport") if cluster else None
    return max(cluster, key=lambda a: _score(a, sport))
