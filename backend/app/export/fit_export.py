"""Build Garmin/TrainingPeaks-compatible .fit structured workout files.

FIT custom-target encodings (consuming-app conventions we follow):
  * Power:  values >= 1000 are absolute watts, stored as ``watts + 1000``.
  * Speed:  stored in m/s (we convert pace targets to a speed range).
  * HR:     values >= 100 are absolute bpm, stored as ``bpm + 100``.

The same file imports into TrainingPeaks' Workout Library and loads directly
onto a Garmin via Garmin Connect, which is our fallback path.
"""

from __future__ import annotations

from datetime import date, datetime
from datetime import time as dtime
from datetime import timezone

from fit_tool.fit_file_builder import FitFileBuilder
from fit_tool.profile.messages.file_id_message import FileIdMessage
from fit_tool.profile.messages.workout_message import WorkoutMessage
from fit_tool.profile.messages.workout_step_message import WorkoutStepMessage
from fit_tool.profile.profile_type import (
    FileType,
    Intensity,
    Manufacturer,
    Sport,
    WorkoutStepDuration,
    WorkoutStepTarget,
)

_SPORT = {
    "Bike": Sport.CYCLING,
    "Run": Sport.RUNNING,
    "Swim": Sport.SWIMMING,
    "Brick": Sport.CYCLING,
    "Strength": Sport.TRAINING,
}

_INTENSITY = {
    "warmup": Intensity.WARMUP,
    "cooldown": Intensity.COOLDOWN,
    "recovery": Intensity.RECOVERY,
    "interval": Intensity.INTERVAL,
    "steady": Intensity.ACTIVE,
}


def _pace_to_speed(pace_sec: float, unit: str) -> float:
    """Convert a pace target to speed in m/s."""
    if pace_sec <= 0:
        return 0.0
    if unit == "sec_per_km":
        return 1000.0 / pace_sec
    if unit == "sec_per_100m":
        return 100.0 / pace_sec
    return 0.0


def _apply_target(step_msg: WorkoutStepMessage, target: dict | None) -> None:
    if not target or target.get("type") in (None, "open"):
        step_msg.target_type = WorkoutStepTarget.OPEN
        step_msg.target_value = 0
        return

    ttype = target["type"]
    unit = target.get("unit", "")
    low, high = target.get("low"), target.get("high")

    if ttype == "power" and low and high:
        step_msg.target_type = WorkoutStepTarget.POWER
        step_msg.target_value = 0  # custom range
        step_msg.custom_target_power_low = int(low) + 1000
        step_msg.custom_target_power_high = int(high) + 1000
    elif ttype == "pace" and low and high:
        # low/high are seconds (low = faster). Convert to a speed range.
        s_fast = _pace_to_speed(min(low, high), unit)
        s_slow = _pace_to_speed(max(low, high), unit)
        step_msg.target_type = WorkoutStepTarget.SPEED
        step_msg.target_value = 0
        # FIT custom_target_speed uses scale 1000 (mm/s); fit-tool stores the
        # raw integer, so we pre-scale here.
        step_msg.custom_target_speed_low = int(round(s_slow * 1000))
        step_msg.custom_target_speed_high = int(round(s_fast * 1000))
    elif ttype == "hr" and low and high:
        step_msg.target_type = WorkoutStepTarget.HEART_RATE
        step_msg.target_value = 0
        step_msg.custom_target_heart_rate_low = int(low) + 100
        step_msg.custom_target_heart_rate_high = int(high) + 100
    else:
        step_msg.target_type = WorkoutStepTarget.OPEN
        step_msg.target_value = 0


def _build_step(index: int, step: dict) -> WorkoutStepMessage:
    msg = WorkoutStepMessage()
    msg.message_index = index
    msg.intensity = _INTENSITY.get(step.get("type"), Intensity.ACTIVE)
    if step.get("notes"):
        msg.workout_step_name = str(step["notes"])[:50]

    dur_s = step.get("duration_s")
    dist_m = step.get("distance_m")
    if dur_s:
        msg.duration_type = WorkoutStepDuration.TIME
        msg.duration_time = float(dur_s)  # seconds — fit-tool applies the ×1000 (ms) scale
    elif dist_m:
        msg.duration_type = WorkoutStepDuration.DISTANCE
        msg.duration_distance = float(dist_m)  # meters — fit-tool applies the ×100 (cm) scale
    else:
        msg.duration_type = WorkoutStepDuration.OPEN

    _apply_target(msg, step.get("target"))
    return msg


def _created_ms(date_str: str | None) -> int:
    """time_created in ms since the Unix epoch — required by Garmin/TrainingPeaks.
    Use the planned date (noon UTC) so the library entry is dated sensibly."""
    try:
        dt = datetime.combine(date.fromisoformat(date_str), dtime(12, 0), tzinfo=timezone.utc)
    except (TypeError, ValueError):
        dt = datetime.now(timezone.utc)
    return round(dt.timestamp() * 1000)


def build_fit(workout: dict) -> bytes:
    """Return the bytes of a .fit structured workout file for one session."""
    sport = _SPORT.get(workout.get("sport"), Sport.GENERIC)
    steps = workout.get("steps") or []

    builder = FitFileBuilder(auto_define=True)

    file_id = FileIdMessage()
    file_id.type = FileType.WORKOUT
    file_id.manufacturer = Manufacturer.DEVELOPMENT.value
    file_id.product = 0
    file_id.serial_number = 0x12345678
    file_id.time_created = _created_ms(workout.get("date"))
    builder.add(file_id)

    wkt = WorkoutMessage()
    wkt.workout_name = (workout.get("title") or "Workout")[:30]
    wkt.sport = sport
    wkt.num_valid_steps = len(steps)
    builder.add(wkt)

    for i, step in enumerate(steps):
        builder.add(_build_step(i, step))

    return builder.build().to_bytes()
