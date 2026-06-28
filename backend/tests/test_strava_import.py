"""Strava bulk-export (GDPR archive) importer."""

import gzip
import io
import zipfile

from starlette.testclient import TestClient

from app import repo, strava_import
from app.main import app
from app.metrics import normalized_power

# A tiny GPX with HR + power extensions on each trackpoint (40 points so NP is defined).
_GPX_POINTS = "".join(
    f'<trkpt lat="40.0" lon="-74.0"><extensions>'
    f'<gpxtpx:TrackPointExtension><gpxtpx:hr>{150 + (i % 5)}</gpxtpx:hr>'
    f'</gpxtpx:TrackPointExtension><power>{200 + (i % 10)}</power>'
    f"</extensions></trkpt>"
    for i in range(40)
)
_GPX = (
    '<?xml version="1.0"?>'
    '<gpx xmlns="http://www.topografix.com/GPX/1/1" '
    'xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">'
    f"<trk><trkseg>{_GPX_POINTS}</trkseg></trk></gpx>"
).encode()

_CSV = (
    "Activity ID,Activity Date,Activity Name,Activity Type,Elapsed Time,Moving Time,"
    "Distance,Max Heart Rate,Average Heart Rate,Average Watts,Average Speed,"
    "Elevation Gain,Filename\n"
    # row 1: a ride with a .gpx.gz file (streams → power/HR/NP)
    "5001,\"Apr 30, 2026, 7:00:00 AM\",Morning Ride,Ride,3600,3500,30000,175,150,210,8.5,"
    "120,activities/5001.gpx.gz\n"
    # row 2: a treadmill run, no file (CSV summary only)
    "5002,\"May 1, 2026, 6:00:00 PM\",Treadmill,Run,1800,1800,5000,180,165,,2.7,0,\n"
)


def _make_zip() -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("activities.csv", _CSV)
        zf.writestr("activities/5001.gpx.gz", gzip.compress(_GPX))
    return buf.getvalue()


def test_normalized_power_math():
    assert normalized_power([200] * 60) == 200          # constant power → NP == avg
    assert normalized_power([100] * 10) is None          # too little data


def test_parse_archive_csv_and_streams(tmp_path):
    p = tmp_path / "export.zip"
    p.write_bytes(_make_zip())
    acts = {a["id"]: a for a in strava_import.parse_archive(str(p))}
    assert set(acts) == {5001, 5002}

    ride = acts[5001]
    assert ride["type"] == "Ride"
    assert ride["distance"] == 30000
    assert ride["start_date_local"].startswith("2026-04-30T07:00")
    # GPX streams enriched power → NP present, device_watts set, HR from stream.
    assert ride.get("device_watts") is True
    assert ride.get("weighted_average_watts") is not None
    assert 200 <= ride["average_watts"] <= 215
    assert ride.get("max_heartrate") == 154

    run = acts[5002]  # no file → CSV summary only
    assert run["type"] == "Run" and run["distance"] == 5000
    assert run.get("device_watts") is None
    assert run.get("_filename") is None  # popped during import


def test_import_archive_endpoint_loads_and_builds_metrics(tmp_path):
    zip_bytes = _make_zip()
    with TestClient(app) as c:
        r = c.post("/api/strava/import",
                   files={"file": ("export.zip", zip_bytes, "application/zip")})
        assert r.status_code == 200
        out = r.json()
        assert out["parsed"] == 2
        assert out["with_streams"] == 1
        assert out["upserted"] == 2
        assert out["metrics_days"] >= 1
    assert repo.activity_count() == 2


def test_import_rejects_non_export_zip(tmp_path):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("random.txt", "nope")
    with TestClient(app) as c:
        r = c.post("/api/strava/import",
                   files={"file": ("x.zip", buf.getvalue(), "application/zip")})
        assert r.status_code == 400
