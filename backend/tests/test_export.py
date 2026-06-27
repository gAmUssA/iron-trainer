import io
import zipfile

from fit_tool.fit_file import FitFile

from app.export.fit_export import build_fit
from app.export.service import bundle_zip
from app.export.zwo_export import build_zwo

BIKE = {
    "date": "2026-07-06", "sport": "Bike", "title": "Threshold intervals",
    "description": "3x10 @ FTP", "steps": [
        {"type": "warmup", "duration_s": 600, "target": {"type": "power", "unit": "W", "low": 140, "high": 160}},
        {"type": "steady", "duration_s": 1800, "target": {"type": "power", "unit": "W", "low": 218, "high": 241}},
        {"type": "cooldown", "duration_s": 300, "target": {"type": "power", "unit": "W", "low": 120, "high": 140}},
    ],
}
RUN = {
    "date": "2026-07-07", "sport": "Run", "title": "Tempo run", "steps": [
        {"type": "warmup", "duration_s": 600, "target": {"type": "pace", "unit": "sec_per_km", "low": 360, "high": 390}},
        {"type": "steady", "duration_s": 1500, "target": {"type": "pace", "unit": "sec_per_km", "low": 320, "high": 335}},
    ],
}
SWIM = {
    "date": "2026-07-08", "sport": "Swim", "title": "CSS set", "steps": [
        {"type": "steady", "distance_m": 1500, "target": {"type": "pace", "unit": "sec_per_100m", "low": 98, "high": 104}},
    ],
}


def _read_steps(data: bytes):
    ff = FitFile.from_bytes(data)
    steps, workout = [], None
    for r in ff.records:
        m = r.message
        cls = type(m).__name__
        if cls == "WorkoutStepMessage":
            steps.append(m)
        elif cls == "WorkoutMessage":
            workout = m
    return workout, steps


def test_fit_bike_power_roundtrip():
    workout, steps = _read_steps(build_fit(BIKE))
    assert workout.num_valid_steps == 3
    assert len(steps) == 3
    # Power encoded as watts + 1000.
    assert steps[1].custom_target_power_low == 1218
    assert steps[1].custom_target_power_high == 1241


def test_fit_run_pace_becomes_speed():
    _, steps = _read_steps(build_fit(RUN))
    main = steps[1]
    # 320 s/km -> 3.125 m/s, stored as mm/s (scale 1000). Faster pace = higher speed.
    assert main.custom_target_speed_high > main.custom_target_speed_low > 0
    assert abs(main.custom_target_speed_high / 1000 - 1000 / 320) < 0.05


def test_fit_swim_distance_duration():
    _, steps = _read_steps(build_fit(SWIM))
    assert len(steps) == 1
    # Distance-based duration survives the round trip (meters).
    assert abs(steps[0].duration_distance - 1500) < 1


def test_fit_time_duration_roundtrip():
    _, steps = _read_steps(build_fit(BIKE))
    # Time-based durations survive in seconds (fit-tool applies the ms scale).
    assert abs(steps[0].duration_time - 600) < 1
    assert abs(steps[1].duration_time - 1800) < 1


def test_fit_has_time_created():
    # TrainingPeaks/Garmin reject a workout file_id without time_created.
    ff = FitFile.from_bytes(build_fit(BIKE))
    file_id = next(
        r.message for r in ff.records if type(r.message).__name__ == "FileIdMessage"
    )
    assert file_id.time_created is not None and file_id.time_created > 0


def test_zwo_only_for_bike_with_ftp():
    assert build_zwo(RUN, 250) is None
    assert build_zwo(BIKE, None) is None
    xml = build_zwo(BIKE, 240)
    assert xml and "<sportType>bike</sportType>" in xml
    assert 'Power="0.95' in xml or 'Power="0.956' in xml  # ~229.5/240


def test_bundle_zip_contains_fit_and_zwo(monkeypatch):
    from app import repo

    repo.save_profile({"ftp": 240})
    data = bundle_zip([BIKE, RUN])
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        names = zf.namelist()
    assert any(n.endswith(".fit") for n in names)
    assert any(n.endswith(".zwo") for n in names)  # bike only
    assert "IMPORT_INSTRUCTIONS.txt" in names
    # Run has a .fit but no .zwo.
    assert sum(n.endswith(".zwo") for n in names) == 1
