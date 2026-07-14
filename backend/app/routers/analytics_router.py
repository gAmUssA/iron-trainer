"""Analytics endpoints feeding the dashboards."""

from __future__ import annotations

from datetime import date

from fastapi import APIRouter

from .. import dashboards, insights, readiness, repo

router = APIRouter(prefix="/api", tags=["analytics"])


@router.get("/metrics/pmc")
def pmc() -> dict:
    """Performance Management Chart: CTL (fitness), ATL (fatigue), TSB (form)."""
    return {"days": repo.get_metrics()}


@router.get("/metrics/weekly")
def weekly(weeks: int = 16) -> dict:
    """Weekly training volume by sport (actual; planned filled in by planner)."""
    return {"weeks": dashboards.weekly_volume(repo.list_activities(), weeks=weeks)}


@router.get("/metrics/trends")
def trends() -> dict:
    """Per-sport progression points plus derived insights: rolling trendlines,
    improving/declining verdicts, weekly intensity mix, PRs, the CTL race-day
    trajectory, and data freshness."""
    activities = repo.list_activities()
    sport_points = dashboards.sport_trends(activities)
    race = repo.effective_race()
    try:
        race_date = date.fromisoformat(str(race.get("date"))[:10])
    except (ValueError, TypeError):
        race_date = None
    return {
        **sport_points,
        "insights": insights.build(activities, sport_points, repo.get_metrics(), race_date),
    }


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
