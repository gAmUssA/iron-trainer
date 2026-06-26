import time
from datetime import date, datetime, timedelta, timezone

from app import analysis, repo, services, strava


def _fake_activities(today: date) -> list[dict]:
    """Three rides + three runs + two swims over the last ~6 weeks."""
    acts = []
    aid = 1
    for wk in range(6):
        d = today - timedelta(days=wk * 7 + 1)
        ts = datetime(d.year, d.month, d.day, 8, 0, tzinfo=timezone.utc).isoformat()
        # Ride with power meter
        acts.append({
            "id": aid, "type": "Ride", "sport_type": "Ride", "name": "Endurance ride",
            "start_date_local": ts, "moving_time": 5400, "elapsed_time": 5500,
            "distance": 45000, "average_watts": 190, "weighted_average_watts": 205,
            "device_watts": True, "average_heartrate": 145, "max_heartrate": 172,
            "average_speed": 8.3, "total_elevation_gain": 350,
        })
        aid += 1
        # Run
        acts.append({
            "id": aid, "type": "Run", "sport_type": "Run", "name": "Tempo run",
            "start_date_local": ts, "moving_time": 2700, "elapsed_time": 2750,
            "distance": 9000, "average_heartrate": 158, "max_heartrate": 180,
            "average_speed": 3.33, "total_elevation_gain": 40,
        })
        aid += 1
        if wk % 3 == 0:
            acts.append({
                "id": aid, "type": "Swim", "sport_type": "Swim", "name": "Pool swim",
                "start_date_local": ts, "moving_time": 1800, "elapsed_time": 1850,
                "distance": 2000, "average_speed": 1.11,
            })
            aid += 1
    return acts


def test_upsert_infer_and_metrics_end_to_end():
    today = date(2026, 6, 25)
    raw = _fake_activities(today)

    # 1) Ingest raw activities (no thresholds yet).
    n = repo.upsert_activities(raw)
    assert n == len(raw)
    assert repo.activity_count() == len(raw)

    # Upsert is idempotent.
    assert repo.upsert_activities(raw) == len(raw)
    assert repo.activity_count() == len(raw)

    # 2) Infer thresholds from history and persist.
    profile = analysis.infer_profile(repo.list_activities(), today=today)
    assert profile.ftp is not None and profile.ftp > 0
    assert profile.threshold_pace_run is not None
    assert profile.css_swim is not None
    assert profile.weekly_hours_target is not None
    repo.save_profile(profile.as_dict())
    a = repo.get_athlete()
    assert a["ftp"] == profile.ftp

    # 3) Recompute TSS now that we have thresholds; bike should use power.
    repo.recompute_tss()
    acts = repo.list_activities()
    bike = next(x for x in acts if x["sport"] == "Bike")
    assert bike["tss_method"] == "power"
    assert bike["tss"] > 0

    # 4) Build the PMC series.
    days = repo.rebuild_metrics(today=today)
    assert days > 0
    metrics_rows = repo.get_metrics()
    assert metrics_rows[-1]["date"] == today.isoformat()
    assert metrics_rows[-1]["ctl"] > 0


def test_run_sync_with_mocked_strava(monkeypatch):
    today = date.today()
    raw = _fake_activities(today)
    monkeypatch.setattr(strava, "fetch_activities", lambda token, after=None: raw)

    repo.save_tokens(1, {
        "access_token": "access",
        "refresh_token": "refresh",
        "expires_at": int(time.time()) + 99999,
        "athlete": {"id": 42, "firstname": "Test", "lastname": "Athlete"},
    })

    result = services.run_sync(full=True)
    assert result["fetched"] == len(raw)
    assert result["total_activities"] == len(raw)
    assert result["profile_seeded"] is True
    assert result["metrics_days"] > 0


def test_upsert_collapses_duplicate_ids_in_batch():
    # Same id twice in one batch must not error (Postgres ON CONFLICT) and keeps last.
    n = repo.upsert_activities([
        {"id": 5001, "type": "Run", "sport_type": "Run",
         "start_date_local": "2026-06-20T08:00:00Z", "moving_time": 1800, "distance": 5000},
        {"id": 5001, "type": "Run", "sport_type": "Run",
         "start_date_local": "2026-06-20T08:00:00Z", "moving_time": 2000, "distance": 6000},
    ])
    assert n == 1
    assert repo.activity_count() == 1
    act = repo.list_activities(include_duplicates=True)[0]
    assert act["moving_time"] == 2000 and act["distance"] == 6000  # last wins
