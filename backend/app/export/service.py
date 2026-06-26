"""Assemble exportable workout files and zip bundles from stored plans."""

from __future__ import annotations

import io
import re
import zipfile

from .. import repo
from .fit_export import build_fit
from .zwo_export import build_zwo


def _slug(text: str) -> str:
    return re.sub(r"[^A-Za-z0-9]+", "-", (text or "").strip()).strip("-")[:40] or "workout"


def filename(workout: dict, ext: str) -> str:
    return f"{workout.get('date')}_{workout.get('sport')}_{_slug(workout.get('title'))}.{ext}"


def _ftp() -> float | None:
    return repo.get_athlete().get("ftp")


def workout_fit(workout: dict) -> tuple[str, bytes]:
    return filename(workout, "fit"), build_fit(workout)


def workout_zwo(workout: dict) -> tuple[str, str] | None:
    content = build_zwo(workout, _ftp())
    if content is None:
        return None
    return filename(workout, "zwo"), content


def bundle_zip(workouts: list[dict]) -> bytes:
    """Zip the .fit for every workout plus .zwo for bike sessions."""
    ftp = _ftp()
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for w in workouts:
            zf.writestr(filename(w, "fit"), build_fit(w))
            zwo = build_zwo(w, ftp)
            if zwo:
                zf.writestr(filename(w, "zwo"), zwo)
        # A short README so the user knows how to import.
        zf.writestr("IMPORT_INSTRUCTIONS.txt", _IMPORT_README)
    return buf.getvalue()


def week_workouts(week_start: str) -> list[dict]:
    from datetime import date, timedelta

    end = (date.fromisoformat(week_start) + timedelta(days=6)).isoformat()
    return [w for w in repo.get_workouts() if week_start <= (w.get("date") or "") <= end]


_IMPORT_README = """Iron Trainer — importing your workouts
======================================

TrainingPeaks (Premium):
  1. Open trainingpeaks.com -> Workout Library.
  2. Click the (...) menu on a folder -> "Workout Import".
  3. Select the .fit files (or .zwo for bike). Workouts land in your library.
  4. Drag each onto your calendar date, or use the planned dates in the filename.
  5. With TP Premium connected to Garmin/Wahoo, the structured workout syncs to
     your device automatically.

Garmin Connect (fallback / direct):
  1. Garmin Connect -> Training -> Workouts -> Import.
  2. Select the .fit file, then "Send to Device".

Filenames encode the planned date and sport, e.g. 2026-07-06_Bike_Long-ride.fit
"""
