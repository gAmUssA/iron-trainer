"""Higher-level orchestration tying Strava, storage, analysis and metrics."""

from __future__ import annotations

import time
from datetime import date

import httpx

from . import analysis, dedup, repo, strava
from .auth import current_athlete_id
from .config import get_settings


class NotConnected(Exception):
    """Raised when Strava has not been authorised yet."""


def valid_access_token() -> str:
    a = repo.get_athlete()
    refresh = a.get("strava_refresh_token")
    if not refresh:
        raise NotConnected("Strava is not connected. Visit /api/strava/connect first.")
    expires_at = a.get("strava_token_expires_at") or 0
    if expires_at <= int(time.time()) + 60:
        token = strava.refresh_access_token(refresh)
        repo.save_tokens(current_athlete_id(), token)
        return token["access_token"]
    return a["strava_access_token"]


def seed_profile_if_empty(*, today: date | None = None) -> dict | None:
    """Infer thresholds from history only if the athlete hasn't set them yet."""
    a = repo.get_athlete()
    already = any(a.get(k) for k in ("ftp", "threshold_hr", "threshold_pace_run", "css_swim"))
    if already:
        return None
    profile = analysis.infer_profile(repo.list_activities(), today=today or date.today())
    repo.save_profile(profile.as_dict())
    # Re-cost activities now that thresholds exist.
    repo.recompute_tss()
    return profile.as_dict()


def deduplicate(
    *,
    token: str | None = None,
    fetch_details: bool = True,
    max_fetches: int | None = None,
) -> dict:
    """Find same-event duplicate activities and keep one each (Apple Watch first).

    For activities in a duplicate cluster that don't yet have a device name we
    fetch the detailed activity (one Strava call each) to learn the device, then
    cache it so later runs don't refetch. `max_fetches` bounds the calls per run
    so a single request stays responsive and survives Strava rate limits — the
    work resumes on the next run because device names are cached.
    """
    repo.clear_duplicate_flags()
    acts = repo.list_activities(include_duplicates=True)
    clusters = dedup.cluster_duplicates(acts)

    # 1) Resolve device names for clustered members we haven't inspected yet.
    need = [a for c in clusters for a in c if not a.get("device_name")]
    fetched = 0
    if token and fetch_details and need:
        cap = max_fetches if max_fetches else len(need)
        for a in need[:cap]:
            try:
                detail = strava.fetch_activity_detail(token, a["id"])
            except httpx.HTTPStatusError as e:
                if e.response.status_code == 429:
                    break  # rate limited — resume next run (names are cached)
                continue
            except Exception:  # noqa: BLE001 - detail is best-effort
                continue
            a["device_name"] = detail.get("device_name")
            repo.set_device_name(a["id"], a["device_name"])
            fetched += 1

    # 2) Mark duplicates, keeping the best activity in each cluster.
    duplicates = 0
    for cluster in clusters:
        primary = dedup.primary_of(cluster)
        for a in cluster:
            is_dup = a["id"] != primary["id"]
            repo.mark_duplicate(a["id"], primary["id"], is_dup)
            duplicates += int(is_dup)

    device_remaining = sum(1 for c in clusters for a in c if not a.get("device_name"))
    return {
        "clusters": len(clusters),
        "duplicates": duplicates,
        "device_fetched": fetched,
        "device_remaining": device_remaining,
    }


def run_sync(*, full: bool = False) -> dict:
    settings = get_settings()
    token = valid_access_token()
    # Full backfill only pulls the retained window; incremental continues from
    # the latest stored activity.
    if full:
        after = settings.history_cutoff_epoch
    else:
        after = repo.latest_activity_epoch()
    raw = strava.fetch_activities(token, after=after)
    upserted = repo.upsert_activities(raw)

    # Drop anything older than the retention window (also prunes previously
    # synced history when the window shrinks).
    pruned = 0
    cutoff = settings.history_cutoff_date
    if cutoff:
        pruned = repo.delete_activities_before(cutoff.isoformat())

    # Cap device lookups so a big backfill stays responsive; the rest can be
    # finished from the "Re-run de-dup" button or `uv run iron-dedup`.
    dedup_stats = deduplicate(token=token, max_fetches=200)
    seeded = seed_profile_if_empty()
    days = repo.rebuild_metrics()
    return {
        "fetched": len(raw),
        "upserted": upserted,
        "pruned_old": pruned,
        "total_activities": repo.activity_count(),
        "duplicates_removed": dedup_stats["duplicates"],
        "duplicate_clusters": dedup_stats["clusters"],
        "device_remaining": dedup_stats["device_remaining"],
        "metrics_days": days,
        "profile_seeded": seeded is not None,
        "inferred_profile": seeded,
    }
