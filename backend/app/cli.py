"""Console entry points, exposed via uv (see [project.scripts] in pyproject.toml).

    uv run iron-dedup [--no-fetch] [--throttle S] [--limit N] [--no-rebuild]

DATA_DIR selects which database to operate on (defaults to ./data).
"""

from __future__ import annotations

import argparse
import time

import httpx

from . import dedup as dedup_mod
from . import repo, services, strava
from .config import get_settings
from .db import init_db


def dedup() -> None:
    """Re-run activity de-duplication on an existing DB (no full re-sync).

    Same-event activities recorded by more than one device (e.g. Apple Watch + a
    bike computer) are clustered and one is kept per event — preferring the Apple
    Watch, then HR/power, then the longest recording. Detecting "Apple Watch"
    needs the activity's device name, which only Strava's *detailed* endpoint
    returns; this fetches it (throttled, cached) for clustered activities.

    Device names are cached, so if Strava rate-limits you mid-run, just run it
    again later — it resumes where it left off.
    """
    parser = argparse.ArgumentParser(prog="iron-dedup", description="Re-run activity de-duplication.")
    parser.add_argument("--no-fetch", action="store_true", help="Skip Strava device-name lookups")
    parser.add_argument("--throttle", type=float, default=0.3, help="Seconds between detail fetches")
    parser.add_argument("--limit", type=int, default=0, help="Max device fetches this run (0 = no cap)")
    parser.add_argument("--no-rebuild", action="store_true", help="Don't rebuild CTL/ATL/TSB after")
    args = parser.parse_args()

    init_db()  # applies migrations (adds device_name / is_duplicate / primary_id to old DBs)
    settings = get_settings()
    print(f"DB: {settings.db_path}")

    repo.clear_duplicate_flags()
    acts = repo.list_activities(include_duplicates=True)
    clusters = dedup_mod.cluster_duplicates(acts)
    print(f"Activities: {len(acts)}  |  duplicate clusters: {len(clusters)}")

    # 1) Resolve device names for clustered members we haven't inspected yet.
    need = [a for c in clusters for a in c if not a.get("device_name")]
    if need and not args.no_fetch:
        token = services.valid_access_token()
        cap = args.limit or len(need)
        fetched = 0
        for a in need[:cap]:
            try:
                detail = strava.fetch_activity_detail(token, a["id"])
            except httpx.HTTPStatusError as e:
                if e.response.status_code == 429:
                    print(f"  Rate limited after {fetched} fetches — run again later to continue.")
                    break
                continue
            except Exception:  # noqa: BLE001 - best effort
                continue
            a["device_name"] = detail.get("device_name")
            repo.set_device_name(a["id"], a["device_name"])
            fetched += 1
            if fetched % 25 == 0:
                print(f"  fetched device names: {fetched}/{min(cap, len(need))}")
            time.sleep(args.throttle)
        print(f"Device names fetched this run: {fetched} (remaining: {max(0, len(need) - fetched)})")
    elif need:
        print(
            f"--no-fetch: {len(need)} clustered activities have no device name "
            "(Apple-Watch preference can't apply to those)."
        )

    # 2) Mark duplicates, keeping the best activity in each cluster
    #    (sport-aware: bike computer for cycling, Apple Watch for swim/run).
    duplicates = 0
    for cluster in clusters:
        primary = dedup_mod.primary_of(cluster)
        for a in cluster:
            is_dup = a["id"] != primary["id"]
            repo.mark_duplicate(a["id"], primary["id"], is_dup)
            duplicates += int(is_dup)

    print(f"Duplicates marked: {duplicates}")

    # 3) Recost & rebuild the fitness curve from the de-duplicated set.
    if not args.no_rebuild:
        repo.recompute_tss()
        days = repo.rebuild_metrics()
        kept = len(repo.list_activities())
        print(f"Rebuilt metrics over {days} days from {kept} de-duplicated activities.")


def serve() -> None:
    """Run the API + built frontend (production-style, no reload)."""
    import uvicorn

    uvicorn.run("app.main:app", host="0.0.0.0", port=8000)
