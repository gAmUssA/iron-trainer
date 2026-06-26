from starlette.testclient import TestClient

from app import dedup, repo, services
from app.main import app


def _raw(aid, sport_type, start, moving, **kw):
    base = {
        "id": aid, "type": sport_type, "sport_type": sport_type,
        "start_date_local": start, "moving_time": moving, "elapsed_time": moving + 60,
        "distance": kw.get("distance", 10000),
    }
    base.update(kw)
    return base


# ── Pure clustering / selection ───────────────────────────────────────────────


def test_cluster_detects_same_event_across_devices():
    a = {"id": 1, "sport": "Bike", "start_date": "2026-06-20T08:00:00", "moving_time": 3600}
    b = {"id": 2, "sport": "Bike", "start_date": "2026-06-20T08:02:00", "moving_time": 3650}
    c = {"id": 3, "sport": "Run", "start_date": "2026-06-21T07:00:00", "moving_time": 1800}
    clusters = dedup.cluster_duplicates([a, b, c])
    assert len(clusters) == 1
    assert {x["id"] for x in clusters[0]} == {1, 2}


def test_no_cluster_for_different_sports_same_time():
    a = {"id": 1, "sport": "Bike", "start_date": "2026-06-20T08:00:00", "moving_time": 3600}
    b = {"id": 2, "sport": "Run", "start_date": "2026-06-20T08:00:00", "moving_time": 3600}
    assert dedup.cluster_duplicates([a, b]) == []


def test_cycling_prefers_bike_computer_over_apple_watch():
    # For the bike, the Garmin Edge (power + cadence) beats the Apple Watch.
    watch = {"id": 1, "sport": "Bike", "start_date": "2026-06-20T08:00:00", "moving_time": 3600,
             "device_name": "Apple Watch Series 9", "avg_hr": 150}
    edge = {"id": 2, "sport": "Bike", "start_date": "2026-06-20T08:00:00", "moving_time": 3600,
            "device_name": "Garmin Edge 1040", "avg_hr": 148, "has_power_meter": 1, "weighted_power": 210}
    assert dedup.primary_of([watch, edge])["id"] == 2


def test_running_prefers_apple_watch_over_garmin():
    # For the run, the Apple Watch wins even against another Garmin device.
    watch = {"id": 1, "sport": "Run", "start_date": "2026-06-20T08:00:00", "moving_time": 3600,
             "device_name": "Apple Watch Series 9", "avg_hr": 150}
    garmin = {"id": 2, "sport": "Run", "start_date": "2026-06-20T08:00:00", "moving_time": 3600,
              "device_name": "Garmin Forerunner 965", "avg_hr": 150}
    assert dedup.primary_of([garmin, watch])["id"] == 1


def test_falls_back_to_data_richness_without_watch():
    plain = {"id": 1, "sport": "Run", "start_date": "2026-06-20T08:00:00", "moving_time": 3600}
    rich = {"id": 2, "sport": "Run", "start_date": "2026-06-20T08:00:00", "moving_time": 3600, "avg_hr": 150}
    assert dedup.primary_of([plain, rich])["id"] == 2


# ── End-to-end through the repo/service ───────────────────────────────────────


def test_deduplicate_marks_and_excludes(monkeypatch):
    repo.upsert_activities([
        _raw(1, "Ride", "2026-06-20T08:00:00Z", 3600, average_heartrate=150),
        _raw(2, "Ride", "2026-06-20T08:01:00Z", 3650, average_heartrate=148,
             device_watts=True, weighted_average_watts=205),
        _raw(3, "Run", "2026-06-21T07:00:00Z", 1800),
    ])
    # Apple Watch on #1, Garmin Edge on #2 (set directly so no Strava call is needed).
    repo.set_device_name(1, "Apple Watch Series 9")
    repo.set_device_name(2, "Garmin Edge 530")

    stats = services.deduplicate(token=None)
    assert stats["clusters"] == 1
    assert stats["duplicates"] == 1

    # Cycling prefers the Garmin Edge (#2); the Apple Watch ride (#1) is the dup.
    kept = {a["id"] for a in repo.list_activities()}
    assert kept == {2, 3}
    all_ids = {a["id"] for a in repo.list_activities(include_duplicates=True)}
    assert all_ids == {1, 2, 3}

    dup = next(a for a in repo.list_activities(include_duplicates=True) if a["id"] == 1)
    assert dup["is_duplicate"] == 1
    assert dup["primary_id"] == 2


def test_dedup_is_idempotent_and_metrics_ignore_duplicates():
    repo.upsert_activities([
        _raw(1, "Ride", "2026-06-20T08:00:00Z", 3600),
        _raw(2, "Ride", "2026-06-20T08:00:00Z", 3600),
    ])
    repo.set_device_name(1, "Apple Watch Series 9")
    services.deduplicate(token=None)
    services.deduplicate(token=None)  # second run must not double-count
    assert sum(a["is_duplicate"] for a in repo.list_activities(include_duplicates=True)) == 1
    # Metrics build only from the kept activity.
    repo.save_profile({"ftp": 230})
    repo.recompute_tss()
    days = repo.rebuild_metrics()
    assert days > 0


def test_dedup_endpoint_offline():
    repo.upsert_activities([
        _raw(1, "Ride", "2026-06-20T08:00:00Z", 3600, device_watts=True, weighted_average_watts=210),
        _raw(2, "Ride", "2026-06-20T08:00:00Z", 3600),
        _raw(3, "Run", "2026-06-21T07:00:00Z", 1800),
    ])
    with TestClient(app) as c:
        r = c.post("/api/strava/dedup?fetch=false")
        assert r.status_code == 200
        body = r.json()
        assert body["clusters"] == 1
        assert body["duplicates"] == 1
        assert "metrics_days" in body
    # The bike ride with power is kept; its plain duplicate is excluded.
    assert {a["id"] for a in repo.list_activities()} == {1, 3}
