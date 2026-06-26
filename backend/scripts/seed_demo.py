"""Seed a DEMO database with ~14 weeks of progressing activities so the
dashboards can be viewed without a live Strava connection.

Usage:
    DATA_DIR=./data/demo .venv/bin/python scripts/seed_demo.py
"""

from __future__ import annotations

from datetime import date, datetime, timedelta, timezone

from app import analysis, repo
from app.db import init_db


def build() -> list[dict]:
    today = date(2026, 6, 25)
    acts: list[dict] = []
    aid = 1
    for wk in range(14):
        # Older weeks first; fitness improves over time (power up, pace down).
        weeks_ago = 13 - wk
        base = today - timedelta(days=weeks_ago * 7)
        progress = wk / 13.0  # 0..1

        def ts(offset_days: int) -> str:
            d = base + timedelta(days=offset_days)
            return datetime(d.year, d.month, d.day, 7, 0, tzinfo=timezone.utc).isoformat()

        # 2 rides (one long), power trending up
        np = 185 + int(progress * 35)
        acts.append({
            "id": aid, "type": "Ride", "sport_type": "Ride", "name": "Long ride",
            "start_date_local": ts(5), "moving_time": 7200 + int(progress * 3600),
            "elapsed_time": 7400, "distance": 60000 + int(progress * 20000),
            "average_watts": np - 12, "weighted_average_watts": np, "device_watts": True,
            "average_heartrate": 142, "max_heartrate": 168,
            "average_speed": 8.0 + progress * 1.4, "total_elevation_gain": 500,
        }); aid += 1
        acts.append({
            "id": aid, "type": "Ride", "sport_type": "Ride", "name": "Threshold intervals",
            "start_date_local": ts(2), "moving_time": 3600, "elapsed_time": 3650,
            "distance": 32000, "average_watts": np + 5, "weighted_average_watts": np + 20,
            "device_watts": True, "average_heartrate": 158, "max_heartrate": 178,
            "average_speed": 8.9, "total_elevation_gain": 200,
        }); aid += 1

        # 2 runs, pace improving (sec/km down)
        run_pace = 320 - int(progress * 25)  # sec/km
        acts.append({
            "id": aid, "type": "Run", "sport_type": "Run", "name": "Long run",
            "start_date_local": ts(6), "moving_time": 4200, "elapsed_time": 4250,
            "distance": int(4200 / (run_pace + 20) * 1000), "average_heartrate": 150,
            "max_heartrate": 170, "average_speed": 1000 / (run_pace + 20),
            "total_elevation_gain": 60,
        }); aid += 1
        acts.append({
            "id": aid, "type": "Run", "sport_type": "Run", "name": "Tempo run",
            "start_date_local": ts(3), "moving_time": 2400, "elapsed_time": 2420,
            "distance": int(2400 / run_pace * 1000), "average_heartrate": 162,
            "max_heartrate": 182, "average_speed": 1000 / run_pace,
            "total_elevation_gain": 30,
        }); aid += 1

        # 1-2 swims, pace improving (sec/100m down)
        swim_pace = 110 - int(progress * 8)
        acts.append({
            "id": aid, "type": "Swim", "sport_type": "Swim", "name": "Pool swim",
            "start_date_local": ts(1), "moving_time": int(2500 / 100 * swim_pace),
            "elapsed_time": 2200, "distance": 2500, "average_speed": 100 / swim_pace,
        }); aid += 1
    return acts


def main() -> None:
    init_db()
    today = date(2026, 6, 25)
    acts = build()
    repo.upsert_activities(acts)
    profile = analysis.infer_profile(repo.list_activities(), today=today)
    repo.save_profile(profile.as_dict())
    repo.recompute_tss()
    days = repo.rebuild_metrics(today=today)
    print(f"Seeded {len(acts)} activities; profile={profile.as_dict()}; metrics_days={days}")


if __name__ == "__main__":
    main()
