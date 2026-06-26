"""Build .zwo (Zwift workout XML) files for bike power sessions.

TrainingPeaks imports .zwo into the Workout Library; Zwift expresses power as a
fraction of FTP, so we convert absolute-watt targets using the athlete's FTP.
Returns None for sports/sessions where .zwo isn't the right vehicle (use .fit).
"""

from __future__ import annotations

from xml.sax.saxutils import escape


def build_zwo(workout: dict, ftp: float | None) -> str | None:
    if workout.get("sport") not in ("Bike", "Brick"):
        return None
    if not ftp:
        return None

    segments = []
    for step in workout.get("steps") or []:
        dur = int(step.get("duration_s") or 0)
        if dur <= 0:
            continue
        target = step.get("target") or {}
        if target.get("type") == "power" and target.get("low") and target.get("high"):
            lo = target["low"] / ftp
            hi = target["high"] / ftp
        else:
            lo = hi = 0.6  # easy spin fallback
        kind = step.get("type")
        if kind == "warmup":
            segments.append(f'    <Warmup Duration="{dur}" PowerLow="{lo:.3f}" PowerHigh="{hi:.3f}"/>')
        elif kind == "cooldown":
            segments.append(f'    <Cooldown Duration="{dur}" PowerLow="{lo:.3f}" PowerHigh="{hi:.3f}"/>')
        else:
            mid = (lo + hi) / 2
            segments.append(f'    <SteadyState Duration="{dur}" Power="{mid:.3f}"/>')

    if not segments:
        return None

    name = escape(workout.get("title") or "Bike workout")
    desc = escape(workout.get("description") or "")
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        "<workout_file>\n"
        "  <author>Iron Trainer</author>\n"
        f"  <name>{name}</name>\n"
        f"  <description>{desc}</description>\n"
        "  <sportType>bike</sportType>\n"
        "  <workout>\n" + "\n".join(segments) + "\n  </workout>\n"
        "</workout_file>\n"
    )
