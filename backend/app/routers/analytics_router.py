"""Analytics endpoints feeding the dashboards."""

from __future__ import annotations

from datetime import date, timedelta

from fastapi import APIRouter, Query

from .. import dashboards, insights, readiness, repo

router = APIRouter(prefix="/api", tags=["analytics"])

# Charts default to a recent window — a bulk archive import can hold years of
# daily rows, and shipping them all on every dashboard load helps nobody.
DEFAULT_WINDOW_DAYS = 180


def _window_cutoff(days: int) -> str | None:
    """ISO date lower bound for a `days` window; None means unbounded (all).

    The window is inclusive of today, so `days=180` spans exactly 180 calendar
    days: today-179 .. today (the metrics series includes a row for today)."""
    if days <= 0:
        return None
    return (date.today() - timedelta(days=days - 1)).isoformat()


@router.get("/metrics/pmc")
def pmc(days: int = Query(DEFAULT_WINDOW_DAYS, ge=0, le=3660)) -> dict:
    """Performance Management Chart: CTL (fitness), ATL (fatigue), TSB (form).

    `days` bounds the returned series (0 = full history)."""
    rows = repo.get_metrics()
    total = len(rows)
    cutoff = _window_cutoff(days)
    if cutoff:
        rows = [r for r in rows if str(r["date"]) >= cutoff]
    return {"days": rows, "window_days": days, "total_days": total}


@router.get("/metrics/weekly")
def weekly(weeks: int = 16) -> dict:
    """Weekly training volume by sport (actual; planned filled in by planner)."""
    return {"weeks": dashboards.weekly_volume(repo.list_activities(), weeks=weeks)}


@router.get("/metrics/trends")
def trends(days: int = Query(DEFAULT_WINDOW_DAYS, ge=0, le=3660)) -> dict:
    """Per-sport progression points plus derived insights: rolling trendlines,
    improving/declining verdicts, weekly intensity mix, PRs, the CTL race-day
    trajectory, and data freshness.

    `days` windows only the returned chart points (0 = full history); insights
    and verdicts are always derived from the complete record so a narrow
    window can't flip a verdict or lose a PR."""
    activities = repo.list_activities()
    sport_points = dashboards.sport_trends(activities)
    race = repo.effective_race()
    try:
        race_date = date.fromisoformat(str(race.get("date"))[:10])
    except (ValueError, TypeError):
        race_date = None
    built = insights.build(activities, sport_points, repo.get_metrics(), race_date)
    cutoff = _window_cutoff(days)
    if cutoff:
        sport_points = {
            sport: [p for p in pts if str(p["date"]) >= cutoff]
            for sport, pts in sport_points.items()
        }
    return {**sport_points, "insights": built, "window_days": days}


@router.get("/metrics/readiness/today")
def readiness_today() -> dict:
    """Today's readiness call (go hard / go easy / rest) from the athlete's own
    acute:chronic load, form, and hard-day streak. Reads local data only."""
    return readiness.compute(repo.get_metrics())


@router.get("/metrics/readiness")
def race_readiness() -> dict:
    """Projected 70.3 splits at current fitness."""
    metrics_rows = repo.get_metrics()
    current_ctl = metrics_rows[-1]["ctl"] if metrics_rows else None
    race = repo.effective_race()
    cutoffs = {
        "swim": race["cutoff_swim_s"],
        "bike": race["cutoff_bike_s"],
        "finish": race["cutoff_finish_s"],
    }
    return dashboards.race_readiness(
        repo.list_activities(),
        repo.athlete_thresholds(),
        current_ctl=current_ctl,
        cutoffs=cutoffs,
        distance=race.get("distance"),
    )


@router.get("/activities")
def activities(limit: int = 500, include_duplicates: bool = True) -> dict:
    acts = repo.list_activities(include_duplicates=include_duplicates)
    # Most-recent first for display.
    acts = list(reversed(acts))[:limit]
    dup_count = sum(1 for a in acts if a.get("is_duplicate"))
    return {"count": repo.activity_count(), "duplicates": dup_count, "activities": acts}
