"""Parse a Strava GDPR "Download your data" bulk export (a user-uploaded ZIP) into
Strava-API-shaped activity dicts that feed ``repo.upsert_activities``.

Reads ``activities.csv`` for the summary of every activity, and — when a row points
at a file in ``activities/`` — parses that ``.fit`` / ``.gpx`` / ``.tcx`` (decompressing
``.gz``) for power/HR streams to derive normalized power (which the CSV lacks). Members
are read on demand, so ``media/`` is never touched. Robust by design: any file that
fails to parse falls back to the CSV summary.
"""

from __future__ import annotations

import csv
import gzip
import io
import zipfile
from datetime import datetime

from .logging_config import get_logger
from .metrics import normalized_power

log = get_logger("import")

# activities.csv header → our key. The export is wide + sparse; we bind by name.
# `Activity Date` is non-ISO text, e.g. "Apr 30, 2021, 1:30:45 PM".
_DATE_FORMATS = ("%b %d, %Y, %I:%M:%S %p", "%b %d, %Y, %H:%M:%S", "%Y-%m-%d %H:%M:%S")


def _num(v: str | None) -> float | None:
    if v is None:
        return None
    v = v.strip().replace(",", "")
    if not v:
        return None
    try:
        return float(v)
    except ValueError:
        return None


def _parse_date(v: str | None) -> str | None:
    if not v:
        return None
    v = v.strip()
    for fmt in _DATE_FORMATS:
        try:
            return datetime.strptime(v, fmt).isoformat()
        except ValueError:
            continue
    return v  # store as-is; better than dropping the activity


def _row_to_activity(row: dict) -> dict | None:
    """Map one activities.csv row to a Strava-API-shaped dict (pre-stream-enrichment)."""
    aid = row.get("Activity ID") or row.get("Activity Id")
    if not aid:
        return None
    try:
        act_id = int(str(aid).strip())
    except ValueError:
        return None
    return {
        "id": act_id,
        "type": (row.get("Activity Type") or "").strip() or None,
        "name": (row.get("Activity Name") or "").strip() or None,
        "start_date_local": _parse_date(row.get("Activity Date")),
        "moving_time": _num(row.get("Moving Time")),
        "elapsed_time": _num(row.get("Elapsed Time")),
        "distance": _num(row.get("Distance")),
        "average_heartrate": _num(row.get("Average Heart Rate")),
        "max_heartrate": _num(row.get("Max Heart Rate")),
        "average_watts": _num(row.get("Average Watts")),
        "average_speed": _num(row.get("Average Speed")),
        "total_elevation_gain": _num(row.get("Elevation Gain")),
        "_filename": (row.get("Filename") or "").strip(),
    }


# ── per-activity file parsing → stream-derived metrics ───────────────────────────

def _localname(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _stream_metrics(power: list[float | None], hr: list[float | None]) -> dict:
    out: dict = {}
    pw = [p for p in power if p is not None]
    hrs = [h for h in hr if h is not None]
    if pw:
        out["average_watts"] = round(sum(pw) / len(pw))
        out["device_watts"] = True
        np = normalized_power(power)
        if np is not None:
            out["weighted_average_watts"] = np
    if hrs:
        out["average_heartrate"] = round(sum(hrs) / len(hrs))
        out["max_heartrate"] = round(max(hrs))
    return out


def _parse_fit(data: bytes) -> dict:
    import fitdecode

    power: list[float | None] = []
    hr: list[float | None] = []
    session: dict = {}
    with fitdecode.FitReader(io.BytesIO(data)) as fr:
        for frame in fr:
            if not isinstance(frame, fitdecode.FitDataMessage):
                continue
            if frame.name == "record":
                power.append(frame.get_value("power", fallback=None))
                hr.append(frame.get_value("heart_rate", fallback=None))
            elif frame.name == "session":
                for k in ("normalized_power", "avg_power", "avg_heart_rate", "max_heart_rate"):
                    v = frame.get_value(k, fallback=None)
                    if v is not None:
                        session[k] = v
    out = _stream_metrics(power, hr)
    # FIT session messages carry authoritative summaries — prefer them when present.
    if session.get("normalized_power"):
        out["weighted_average_watts"] = round(session["normalized_power"])
        out["device_watts"] = True
    if session.get("avg_power") and "average_watts" not in out:
        out["average_watts"] = round(session["avg_power"])
    if session.get("avg_heart_rate"):
        out["average_heartrate"] = round(session["avg_heart_rate"])
    if session.get("max_heart_rate"):
        out["max_heartrate"] = round(session["max_heart_rate"])
    return out


def _parse_gpx(data: bytes) -> dict:
    # Scan all elements by local tag name — robust across the gpxtpx/garmin/strava
    # extension namespaces without depending on a GPX library's extension handling.
    import xml.etree.ElementTree as ET

    power: list[float | None] = []
    hr: list[float | None] = []
    for el in ET.fromstring(data).iter():
        if el.text is None:
            continue
        name = _localname(el.tag).lower()
        try:
            val = float(el.text)
        except ValueError:
            continue
        if name in ("power", "watts"):
            power.append(val)
        elif name in ("hr", "heartrate"):
            hr.append(val)
    return _stream_metrics(power, hr)


def _parse_tcx(data: bytes) -> dict:
    import xml.etree.ElementTree as ET

    power: list[float | None] = []
    hr: list[float | None] = []
    root = ET.fromstring(data)
    for tp in root.iter():
        if _localname(tp.tag) != "Trackpoint":
            continue
        for el in tp.iter():
            name = _localname(el.tag)
            if name == "HeartRateBpm":
                for child in el:
                    if _localname(child.tag) == "Value" and child.text:
                        hr.append(float(child.text))
            elif name == "Watts" and el.text:
                power.append(float(el.text))
    return _stream_metrics(power, hr)


_PARSERS = {".fit": _parse_fit, ".gpx": _parse_gpx, ".tcx": _parse_tcx}

# Decompression-bomb guard: no single member (nor its gzip payload) may expand
# beyond this. Real per-activity files are a few MB; activities.csv tens of MB.
MAX_MEMBER_BYTES = 100 * 1024 * 1024


def _parse_activity_file(zf: zipfile.ZipFile, names: set[str], filename: str) -> dict | None:
    """Decompress (if .gz) + parse one activities/ file → stream-derived metrics."""
    name = filename.lstrip("/")
    if name not in names:
        # The CSV Filename sometimes omits/adds the activities/ prefix.
        alt = name if name.startswith("activities/") else f"activities/{name}"
        if alt in names:
            name = alt
        else:
            return None
    if zf.getinfo(name).file_size > MAX_MEMBER_BYTES:
        log.warning("Skipping %s: member exceeds %d bytes.", name, MAX_MEMBER_BYTES)
        return None  # falls back to the CSV summary, like any unparseable file
    raw = zf.read(name)
    if name.endswith(".gz"):
        with gzip.GzipFile(fileobj=io.BytesIO(raw)) as gz:
            raw = gz.read(MAX_MEMBER_BYTES + 1)
        if len(raw) > MAX_MEMBER_BYTES:
            log.warning("Skipping %s: gzip payload exceeds %d bytes.", name, MAX_MEMBER_BYTES)
            return None
        name = name[:-3]
    ext = name[name.rfind(".") :].lower()
    parser = _PARSERS.get(ext)
    if parser is None:
        return None
    return parser(raw)


def parse_archive(zip_path: str) -> list[dict]:
    """Parse a Strava bulk-export ZIP into Strava-API-shaped activity dicts."""
    activities: list[dict] = []
    with zipfile.ZipFile(zip_path) as zf:
        names = set(zf.namelist())
        csv_name = next((n for n in names if n.endswith("activities.csv")), None)
        if csv_name is None:
            raise ValueError("No activities.csv found — is this a Strava data export ZIP?")
        if zf.getinfo(csv_name).file_size > MAX_MEMBER_BYTES:
            raise ValueError("activities.csv is implausibly large — refusing to import.")
        text = zf.read(csv_name).decode("utf-8-sig", errors="replace")
        reader = csv.DictReader(io.StringIO(text))
        parsed = enriched = failed = 0
        for row in reader:
            act = _row_to_activity(row)
            if act is None:
                continue
            parsed += 1
            fname = act.pop("_filename", "")
            if fname:
                try:
                    extra = _parse_activity_file(zf, names, fname)
                    if extra:
                        act.update(extra)
                        enriched += 1
                except Exception as e:  # noqa: BLE001 — never let one bad file abort
                    failed += 1
                    log.debug("Failed to parse %s: %s", fname, e)
            activities.append(act)
    log.info("Parsed export: %d activities (%d enriched from files, %d file errors).",
             parsed, enriched, failed)
    return activities
