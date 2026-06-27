"""Assemble exportable workout files and zip bundles from stored plans."""

from __future__ import annotations

import io
import re
import zipfile

from .. import repo
from .fit_export import build_fit
from .itw_export import build_itw
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


def workout_itw(workout: dict) -> tuple[str, str]:
    return filename(workout, "itw"), build_itw(workout, repo.get_athlete())


def bundle_zip(workouts: list[dict]) -> bytes:
    """Zip the .fit for every workout, plus .zwo for bike and .itw for all."""
    ftp = _ftp()
    athlete = repo.get_athlete()
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for w in workouts:
            zf.writestr(filename(w, "fit"), build_fit(w))
            zwo = build_zwo(w, ftp)
            if zwo:
                zf.writestr(filename(w, "zwo"), zwo)
            zf.writestr(filename(w, "itw"), build_itw(w, athlete))
        # A short README so the user knows how to import.
        zf.writestr("IMPORT_INSTRUCTIONS.txt", _IMPORT_README)
    return buf.getvalue()


def week_workouts(week_start: str) -> list[dict]:
    from datetime import date, timedelta

    end = (date.fromisoformat(week_start) + timedelta(days=6)).isoformat()
    return [w for w in repo.get_workouts() if week_start <= (w.get("date") or "") <= end]


_IMPORT_README = """Iron Trainer — importing your workouts
======================================

Two destinations, two file types:

GARMIN CONNECT — all sports (bike, run, swim). Use the .fit files.
  1. Garmin Connect (web) -> Training & Planning -> Workouts -> Import.
  2. Select a .fit file; it appears as a structured workout.
  3. "Send to Device" (or schedule it on a date) -> it syncs to your Garmin.
  (Wahoo and Suunto also accept these structured workouts.)

TRAININGPEAKS — bike only. Use the .zwo files.
  1. trainingpeaks.com -> Workout Library -> (...) on a folder -> "Workout Import".
  2. Select the .zwo files. TrainingPeaks' import ONLY accepts power-based bike
     .zwo — it does not import .fit, and it cannot import run or swim workouts.
  3. Drag each onto a calendar date. With TP Premium linked to your device, the
     bike workout syncs automatically.

APPLE WORKOUTS (Apple Watch) — all sports. Use the .itw files.
  1. Install the Iron Trainer helper app (iOS 18+) from TestFlight/App Store.
  2. Open a .itw file (from Files, Mail, or AirDrop) — it opens in the app.
  3. Preview the session, then "Open in Workout app" or "Schedule" it on its
     planned date. It syncs to your Apple Watch.
  (.itw is Iron Trainer's own JSON; the helper app builds the native Apple
   workout on-device, since Apple's .workout format can't be generated here.)

So: run & swim (and bike, if you prefer) -> Garmin Connect via .fit.
    Bike into TrainingPeaks -> .zwo.
    Apple Watch -> .itw via the Iron Trainer helper app.

Filenames encode the planned date and sport, e.g. 2026-07-06_Bike_Long-ride.fit
"""
