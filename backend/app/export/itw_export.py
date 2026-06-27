"""Build the Iron Trainer Workout (.itw) intermediate representation.

Apple's native ``.workout`` file is an undocumented binary produced only on-device
by WorkoutKit's ``WorkoutComposition`` export — it cannot be generated on a server.
So the backend emits this neutral, versioned JSON instead. The iOS helper app
reads a ``.itw`` file and builds a real ``CustomWorkout`` / ``WorkoutPlan`` on the
device, then schedules it (using ``date``) or opens it in the Workout app.

The step shape is the same one ``fit_export``/``zwo_export`` consume
(``type``, ``duration_s``, ``distance_m``, ``notes``, ``target{type,low,high,unit}``),
so steps pass through unchanged.
"""

from __future__ import annotations

import json

SCHEMA_VERSION = 1

# Athlete thresholds carried in the file so it is self-contained and the iOS app
# can resolve relative targets without calling back to the server.
_THRESHOLD_KEYS = ("ftp", "threshold_hr", "max_hr", "threshold_pace_run", "css_swim")


def build_itw(workout: dict, athlete: dict | None = None) -> str:
    """Return the JSON string of a .itw file for one planned session."""
    athlete = athlete or {}
    doc = {
        "schema_version": SCHEMA_VERSION,
        "generator": "iron-trainer",
        "date": workout.get("date"),
        "sport": workout.get("sport"),
        "title": workout.get("title"),
        "description": workout.get("description"),
        "duration_s": workout.get("duration_s"),
        "distance_m": workout.get("distance_m"),
        "athlete": {k: athlete.get(k) for k in _THRESHOLD_KEYS},
        "steps": workout.get("steps") or [],
    }
    return json.dumps(doc, ensure_ascii=False, indent=2)
