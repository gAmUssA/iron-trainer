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


def _workout_doc(workout: dict, athlete: dict) -> dict:
    """The per-workout .itw object. Each item is a standalone, decodable workout
    (carries its own schema_version) so it can be read singly or inside a plan."""
    return {
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


def build_itw(workout: dict, athlete: dict | None = None) -> str:
    """Return the JSON string of a .itw file for one planned session."""
    return json.dumps(_workout_doc(workout, athlete or {}), ensure_ascii=False, indent=2)


def build_plan_itw(workouts: list[dict], plan: dict | None, athlete: dict | None = None) -> str:
    """Return the JSON string of a whole-plan .itw the iOS app fetches over HTTP:
    a top-level envelope plus a list of standalone per-workout docs."""
    athlete = athlete or {}
    plan_meta = None
    if plan:
        plan_meta = {
            "race_name": plan.get("race_name"),
            "race_date": plan.get("race_date"),
            "summary": plan.get("summary"),
        }
    doc = {
        "schema_version": SCHEMA_VERSION,
        "generator": "iron-trainer",
        "plan": plan_meta,
        "workouts": [_workout_doc(w, athlete) for w in workouts],
    }
    return json.dumps(doc, ensure_ascii=False, indent=2)
