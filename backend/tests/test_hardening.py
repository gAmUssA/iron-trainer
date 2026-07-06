"""Security/robustness hardening from the 2026-07-06 review
(docs/reviews/2026-07-06-code-review-findings.md): B1 session-secret guard,
B2 decompression caps, B3 device-token revocation, N1 validator aggregation,
N3 profile bounds."""

import gzip
import io
import zipfile
from types import SimpleNamespace

import pytest
from starlette.testclient import TestClient

from app import nutrition as n
from app import repo, strava_import
from app.main import app, enforce_secure_config

# ── B1: refuse multi-user auth with the default session secret ────────────────


def test_default_secret_with_auth_required_refuses_to_start():
    s = SimpleNamespace(auth_required=True, session_secret="dev-insecure-change-me")
    with pytest.raises(RuntimeError, match="SESSION_SECRET"):
        enforce_secure_config(s)


def test_secure_or_local_configs_start_fine():
    enforce_secure_config(SimpleNamespace(auth_required=True, session_secret="s3cr3t-real"))
    enforce_secure_config(SimpleNamespace(auth_required=False, session_secret="dev-insecure-change-me"))


# ── B2: decompression-bomb guards in the archive importer ─────────────────────

_CSV = (
    "Activity ID,Activity Date,Activity Name,Activity Type,Elapsed Time,Moving Time,"
    "Distance,Max Heart Rate,Average Heart Rate,Average Watts,Average Speed,"
    "Elevation Gain,Filename\n"
    "7001,\"Apr 30, 2026, 7:00:00 AM\",Big Ride,Ride,3600,3500,30000,175,150,210,8.5,"
    "120,activities/7001.gpx.gz\n"
)


def _zip_with(member: bytes) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr("activities.csv", _CSV)
        zf.writestr("activities/7001.gpx.gz", member)
    return buf.getvalue()


def test_oversized_member_falls_back_to_csv(tmp_path, monkeypatch):
    monkeypatch.setattr(strava_import, "MAX_MEMBER_BYTES", 400)  # > CSV, < member
    p = tmp_path / "export.zip"
    p.write_bytes(_zip_with(b"x" * 500))  # stored member bigger than the cap
    acts = strava_import.parse_archive(str(p))
    assert len(acts) == 1
    assert acts[0]["id"] == 7001
    assert "device_watts" not in acts[0]  # not enriched — CSV summary only


def test_gzip_bomb_payload_is_skipped(tmp_path, monkeypatch):
    monkeypatch.setattr(strava_import, "MAX_MEMBER_BYTES", 5000)
    bomb = gzip.compress(b"<gpx>" + b"A" * 100_000 + b"</gpx>")  # tiny .gz, big payload
    assert len(bomb) < 5000  # the compressed member itself passes the size check
    p = tmp_path / "export.zip"
    p.write_bytes(_zip_with(bomb))
    acts = strava_import.parse_archive(str(p))
    assert len(acts) == 1 and "device_watts" not in acts[0]


# ── B3: device bearer tokens are revocable ────────────────────────────────────


def test_revoke_endpoint_kills_bearer_tokens():
    aid = repo.find_or_create_athlete(444, "Fourth")
    code = repo.create_pairing_code(aid)["code"]
    token = repo.claim_pairing_code(code)["token"]
    assert repo.athlete_id_for_bearer(token) == aid
    with TestClient(app) as c:
        out = c.delete("/api/device/tokens", headers={"Authorization": f"Bearer {token}"}).json()
    assert out["revoked"] == 1
    assert repo.athlete_id_for_bearer(token) is None


def test_disconnect_purges_device_tokens():
    from app import auth

    aid = repo.find_or_create_athlete(555, "Fifth")
    code = repo.create_pairing_code(aid)["code"]
    token = repo.claim_pairing_code(code)["token"]
    ctx = auth.set_current_athlete_id(aid)
    try:
        summary = repo.disconnect_strava()
    finally:
        auth.reset_current_athlete_id(ctx)
    assert summary["revoked_devices"] == 1
    assert repo.athlete_id_for_bearer(token) is None


# ── N1: validator aggregates per phase and clamps duration-less items ─────────


def test_split_phase_items_are_rate_checked_as_a_sum():
    # Three bike entries, each fine alone (100 g over 3 h ≈ 33 g/h) but 300 g
    # over 3 h = 100 g/h... within cap; use 150 g each = 150 g/h → over 120.
    plan = {"items": [
        {"phase": "bike", "label": f"Bike hour {i}", "phase_duration_s": 3 * 3600,
         "carbs_g": 150, "notes": ""}
        for i in range(3)
    ]}
    fixed, notes = n.validate_fueling(plan)
    total = sum(i["carbs_g"] for i in fixed["items"])
    assert total <= n.MAX_CARB_G_H * 3 + 2  # scaled to fit the phase budget (± rounding)
    assert any("capped" in x for x in notes)


def test_durationless_transition_amount_is_clamped():
    plan = {"items": [{"phase": "t1", "label": "T1", "carbs_g": 400, "fluid_ml": 2000}]}
    fixed, notes = n.validate_fueling(plan)
    assert fixed["items"][0]["carbs_g"] <= n._TRANSITION_MAX_CARB_G
    assert fixed["items"][0]["fluid_ml"] <= n._TRANSITION_MAX_FLUID_ML
    assert len(notes) == 2


def test_durationless_meal_amount_is_clamped():
    plan = {"items": [{"phase": "pre_race", "label": "Breakfast", "carbs_g": 900}]}
    fixed, notes = n.validate_fueling(plan)
    assert fixed["items"][0]["carbs_g"] <= n._MEAL_MAX_CARB_G
    assert notes


# ── N3: profile bounds reject garbage before it poisons the math ──────────────


def test_profile_rejects_out_of_range_values():
    with TestClient(app) as c:
        assert c.put("/api/athlete/profile", json={"body_weight_kg": -70}).status_code == 422
        assert c.put("/api/athlete/profile", json={"gi_tolerance": "extreme"}).status_code == 422
        ok = c.put("/api/athlete/profile", json={"body_weight_kg": 74.5, "gi_tolerance": "high"})
        assert ok.status_code == 200
