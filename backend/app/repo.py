"""Repository layer: read/write athlete, activities, plans and metrics.

Uses SQLModel under the hood, keeps a dict-in/dict-out boundary, and is
**multi-tenant**: every per-user query is scoped to the current athlete
(`auth.current_athlete_id()`), which the auth middleware resolves per request
(or the local-mode default). The race catalog is global. Works on SQLite or
Postgres — the only dialect-specific spot is the activity upsert.
"""

from __future__ import annotations

import json
from datetime import date, datetime, timezone

from sqlalchemy import delete, func, update
from sqlmodel import Session, select

from . import metrics
from .auth import current_athlete_id
from .config import get_settings
from .db import get_session
from .metrics import Thresholds
from .models import Activity, Athlete, MetricDaily, Plan, PlannedWorkout, Race

_ACT_UPDATE_COLS = [
    "sport", "start_date", "name", "moving_time", "elapsed_time", "distance",
    "avg_power", "weighted_power", "avg_hr", "max_hr", "avg_speed",
    "elevation_gain", "has_power_meter", "tss", "intensity_factor", "tss_method",
]


def _upsert(session: Session, rows: list[dict]) -> None:
    """Dialect-aware INSERT … ON CONFLICT(id) DO UPDATE for activities."""
    dialect = session.get_bind().dialect.name
    if dialect == "postgresql":
        from sqlalchemy.dialects.postgresql import insert as _insert
    else:
        from sqlalchemy.dialects.sqlite import insert as _insert
    stmt = _insert(Activity).values(rows)
    stmt = stmt.on_conflict_do_update(
        index_elements=["id"],
        set_={c: getattr(stmt.excluded, c) for c in _ACT_UPDATE_COLS},
    )
    session.execute(stmt)


# ── Athlete (current user) ────────────────────────────────────────────────────


def get_athlete() -> dict:
    with get_session() as s:
        a = s.get(Athlete, current_athlete_id())
        return a.model_dump() if a else {}


def athlete_thresholds() -> Thresholds:
    a = get_athlete()
    return Thresholds(
        ftp=a.get("ftp"), threshold_hr=a.get("threshold_hr"), max_hr=a.get("max_hr"),
        threshold_pace_run=a.get("threshold_pace_run"), css_swim=a.get("css_swim"),
    )


def find_or_create_athlete(strava_athlete_id: int, name: str | None = None) -> int:
    """Look up an athlete by Strava id, creating one if needed. Used at login,
    so it operates by Strava id rather than the current-user context."""
    with get_session() as s:
        a = s.exec(select(Athlete).where(Athlete.strava_athlete_id == strava_athlete_id)).first()
        if a is None:
            a = Athlete(strava_athlete_id=strava_athlete_id, name=name)
            s.add(a)
            s.flush()
        elif name and not a.name:
            a.name = name
        return int(a.id)


def save_tokens(athlete_id: int, token: dict) -> None:
    athlete = token.get("athlete") or {}
    name = " ".join(filter(None, [athlete.get("firstname"), athlete.get("lastname")])) or None
    with get_session() as s:
        a = s.get(Athlete, athlete_id) or Athlete(id=athlete_id)
        if athlete.get("id") is not None:
            a.strava_athlete_id = athlete["id"]
        if name:
            a.name = name
        a.strava_access_token = token.get("access_token")
        a.strava_refresh_token = token.get("refresh_token")
        a.strava_token_expires_at = token.get("expires_at")
        a.updated_at = _now_iso()
        s.add(a)


def save_profile(profile: dict) -> None:
    """Persist inferred/edited thresholds for the current athlete."""
    fields = ["ftp", "threshold_hr", "max_hr", "threshold_pace_run", "css_swim", "weekly_hours_target"]
    with get_session() as s:
        a = s.get(Athlete, current_athlete_id())
        if a is None:
            return
        changed = False
        for f in fields:
            if profile.get(f) is not None:
                setattr(a, f, profile[f])
                changed = True
        if changed:
            a.updated_at = _now_iso()
            s.add(a)


# ── Activities ────────────────────────────────────────────────────────────────


def _map_activity(raw: dict, th: Thresholds, aid: int) -> dict:
    sport = metrics.normalize_sport(raw.get("sport_type") or raw.get("type"))
    moving = raw.get("moving_time")
    distance = raw.get("distance")
    weighted = raw.get("weighted_average_watts")
    avg_power = raw.get("average_watts")
    avg_hr = raw.get("average_heartrate")
    max_hr = raw.get("max_heartrate")
    has_pm = 1 if raw.get("device_watts") else 0
    tss, if_value, method = metrics.compute_tss(
        sport, moving_time=moving, distance=distance, weighted_power=weighted,
        avg_power=avg_power, avg_hr=avg_hr, th=th,
    )
    return {
        "id": raw.get("id"), "athlete_id": aid, "sport": sport,
        "start_date": raw.get("start_date_local") or raw.get("start_date"),
        "name": raw.get("name"), "moving_time": moving, "elapsed_time": raw.get("elapsed_time"),
        "distance": distance, "avg_power": avg_power, "weighted_power": weighted,
        "avg_hr": avg_hr, "max_hr": max_hr, "avg_speed": raw.get("average_speed"),
        "elevation_gain": raw.get("total_elevation_gain"), "has_power_meter": has_pm,
        "tss": tss, "intensity_factor": if_value, "tss_method": method, "created_at": _now_iso(),
    }


def upsert_activities(raw_list: list[dict]) -> int:
    aid = current_athlete_id()
    th = athlete_thresholds()
    rows = [_map_activity(r, th, aid) for r in raw_list if r.get("id")]
    if not rows:
        return 0
    rows = list({row["id"]: row for row in rows}.values())  # dedup batch by id (Postgres ON CONFLICT)
    with get_session() as s:
        _upsert(s, rows)
    return len(rows)


def recompute_tss() -> int:
    aid = current_athlete_id()
    th = athlete_thresholds()
    with get_session() as s:
        acts = s.exec(select(Activity).where(Activity.athlete_id == aid)).all()
        for a in acts:
            a.tss, a.intensity_factor, a.tss_method = metrics.compute_tss(
                a.sport, moving_time=a.moving_time, distance=a.distance,
                weighted_power=a.weighted_power, avg_power=a.avg_power, avg_hr=a.avg_hr, th=th,
            )
            s.add(a)
        return len(acts)


def list_activities(*, include_duplicates: bool = False) -> list[dict]:
    aid = current_athlete_id()
    with get_session() as s:
        stmt = select(Activity).where(Activity.athlete_id == aid)
        if not include_duplicates:
            stmt = stmt.where((Activity.is_duplicate == 0) | (Activity.is_duplicate.is_(None)))
        return [a.model_dump() for a in s.exec(stmt.order_by(Activity.start_date)).all()]


def set_device_name(activity_id: int, device_name: str | None) -> None:
    aid = current_athlete_id()
    with get_session() as s:
        a = s.get(Activity, activity_id)
        if a and a.athlete_id == aid:
            a.device_name = device_name
            s.add(a)


def clear_duplicate_flags() -> None:
    aid = current_athlete_id()
    with get_session() as s:
        s.execute(update(Activity).where(Activity.athlete_id == aid).values(is_duplicate=0, primary_id=None))


def mark_duplicate(activity_id: int, primary_id: int, is_duplicate: bool) -> None:
    aid = current_athlete_id()
    with get_session() as s:
        a = s.get(Activity, activity_id)
        if a and a.athlete_id == aid:
            a.is_duplicate = 1 if is_duplicate else 0
            a.primary_id = primary_id
            s.add(a)


def latest_activity_epoch() -> int | None:
    aid = current_athlete_id()
    with get_session() as s:
        latest = s.execute(
            select(func.max(Activity.start_date)).where(Activity.athlete_id == aid)
        ).scalar()
    if not latest:
        return None
    try:
        dt = datetime.fromisoformat(str(latest).replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return int(dt.timestamp())
    except ValueError:
        return None


def activity_count() -> int:
    aid = current_athlete_id()
    with get_session() as s:
        return s.execute(
            select(func.count()).select_from(Activity).where(Activity.athlete_id == aid)
        ).scalar_one()


def delete_activities_before(cutoff_date_iso: str) -> int:
    aid = current_athlete_id()
    with get_session() as s:
        res = s.execute(
            delete(Activity).where(Activity.athlete_id == aid, Activity.start_date < cutoff_date_iso)
        )
        return res.rowcount


# ── Daily metrics ─────────────────────────────────────────────────────────────


def store_metrics(days: list[metrics.DayMetric]) -> None:
    aid = current_athlete_id()
    with get_session() as s:
        s.execute(delete(MetricDaily).where(MetricDaily.athlete_id == aid))
        s.add_all([
            MetricDaily(athlete_id=aid, date=d.day.isoformat(), tss=d.tss, ctl=d.ctl, atl=d.atl, tsb=d.tsb)
            for d in days
        ])


def get_metrics() -> list[dict]:
    aid = current_athlete_id()
    with get_session() as s:
        rows = s.exec(
            select(MetricDaily).where(MetricDaily.athlete_id == aid).order_by(MetricDaily.date)
        ).all()
        return [r.model_dump() for r in rows]


def rebuild_metrics(today: date | None = None) -> int:
    today = today or date.today()
    pairs: list[tuple[date, float]] = []
    for a in list_activities():
        try:
            day = datetime.fromisoformat(str(a["start_date"]).replace("Z", "+00:00")).date()
        except (ValueError, TypeError):
            continue
        pairs.append((day, a.get("tss") or 0.0))
    days = metrics.performance_management(pairs, end=today)
    store_metrics(days)
    return len(days)


# ── Races catalog (global) + per-athlete race ─────────────────────────────────

_CUTOFFS = {"70.3": (4200, 19800, 30600), "140.6": (8400, 37800, 61200)}


def cutoffs_for(distance: str | None) -> tuple[int, int, int]:
    return _CUTOFFS.get(str(distance), _CUTOFFS["70.3"])


def seed_races(items: list[dict]) -> int:
    with get_session() as s:
        existing = {r.slug: r for r in s.exec(select(Race)).all()}
        for it in items:
            cs, cb, cf = cutoffs_for(it.get("distance"))
            r = existing.get(it["slug"]) or Race(slug=it["slug"])
            r.name, r.date, r.distance = it["name"], it["date"], it["distance"]
            r.city, r.country = it.get("city"), it.get("country")
            r.cutoff_swim_s, r.cutoff_bike_s, r.cutoff_finish_s = cs, cb, cf
            s.add(r)
    return len(items)


def list_races(
    *, distance: str | None = None, country: str | None = None,
    month: str | None = None, q: str | None = None,
) -> list[dict]:
    with get_session() as s:
        stmt = select(Race)
        if distance:
            stmt = stmt.where(Race.distance == distance)
        if country:
            stmt = stmt.where(Race.country == country)
        if month:
            stmt = stmt.where(Race.date >= f"{month}-01", Race.date <= f"{month}-31")
        if q:
            like = f"%{q.lower()}%"
            stmt = stmt.where(func.lower(Race.name).like(like) | func.lower(Race.city).like(like))
        return [r.model_dump() for r in s.exec(stmt.order_by(Race.date)).all()]


def get_race(race_id: int) -> dict | None:
    with get_session() as s:
        r = s.get(Race, race_id)
        return r.model_dump() if r else None


def set_athlete_race(
    *, race_id: int | None = None, name: str | None = None,
    race_date: str | None = None, distance: str | None = None,
) -> dict:
    with get_session() as s:
        a = s.get(Athlete, current_athlete_id())
        if a is None:
            raise ValueError("No current athlete")
        if race_id is not None:
            r = s.get(Race, race_id)
            if r is None:
                raise ValueError(f"Race {race_id} not found")
            a.race_id = r.id
            a.race_name, a.race_date, a.race_distance = r.name, r.date, r.distance
            a.cutoff_swim_s, a.cutoff_bike_s, a.cutoff_finish_s = (
                r.cutoff_swim_s, r.cutoff_bike_s, r.cutoff_finish_s,
            )
        else:
            if not (name and race_date):
                raise ValueError("Custom race needs name and date")
            a.race_id = None
            a.race_name, a.race_date, a.race_distance = name, race_date, distance
            a.cutoff_swim_s, a.cutoff_bike_s, a.cutoff_finish_s = cutoffs_for(distance)
        a.updated_at = _now_iso()
        s.add(a)
    return effective_race()


def effective_race() -> dict:
    a = get_athlete()
    s = get_settings()
    if a.get("race_date"):
        return {
            "name": a.get("race_name") or s.race_name,
            "date": a["race_date"],
            "distance": a.get("race_distance"),
            "cutoff_swim_s": a.get("cutoff_swim_s") or s.cutoff_swim_s,
            "cutoff_bike_s": a.get("cutoff_bike_s") or s.cutoff_bike_s,
            "cutoff_finish_s": a.get("cutoff_finish_s") or s.cutoff_finish_s,
        }
    return {
        "name": s.race_name, "date": s.race_date.isoformat(), "distance": None,
        "cutoff_swim_s": s.cutoff_swim_s, "cutoff_bike_s": s.cutoff_bike_s,
        "cutoff_finish_s": s.cutoff_finish_s,
    }


# ── Plans & planned workouts ──────────────────────────────────────────────────


def save_plan(season: dict) -> int:
    aid = current_athlete_id()
    with get_session() as s:
        s.execute(
            update(Plan).where(Plan.athlete_id == aid, Plan.status == "active").values(status="superseded")
        )
        p = Plan(
            athlete_id=aid, race_name=season.get("race_name"), race_date=season.get("race_date"),
            status="active", summary=season.get("summary"),
            weeks_json=json.dumps(season.get("weeks", [])), created_at=_now_iso(),
        )
        s.add(p)
        s.flush()
        return int(p.id)


def get_active_plan() -> dict | None:
    aid = current_athlete_id()
    with get_session() as s:
        p = s.exec(
            select(Plan).where(Plan.athlete_id == aid, Plan.status == "active").order_by(Plan.id.desc())
        ).first()
        return _plan_dict(p) if p else None


def save_workouts(plan_id: int, workouts: list[dict], *, replace_all: bool = True) -> int:
    aid = current_athlete_id()
    with get_session() as s:
        if replace_all:
            s.execute(delete(PlannedWorkout).where(PlannedWorkout.plan_id == plan_id))
        s.add_all([
            PlannedWorkout(
                athlete_id=aid, plan_id=plan_id, date=w.get("date"), sport=w.get("sport"),
                title=w.get("title"), description=w.get("description"),
                structure_json=json.dumps(w.get("steps", [])), duration_s=w.get("duration_s"),
                distance_m=w.get("distance_m"), planned_tss=w.get("planned_tss"),
                intensity=w.get("intensity"), created_at=_now_iso(),
            )
            for w in workouts
        ])
    return len(workouts)


def replace_week_workouts(plan_id: int, week_start: str, week_end: str, workouts: list[dict]) -> int:
    with get_session() as s:
        s.execute(
            delete(PlannedWorkout).where(
                PlannedWorkout.plan_id == plan_id,
                PlannedWorkout.date >= week_start, PlannedWorkout.date <= week_end,
            )
        )
    return save_workouts(plan_id, workouts, replace_all=False)


def set_workout_status(workout_id: int, status: str, matched_activity_id: int | None) -> None:
    aid = current_athlete_id()
    with get_session() as s:
        w = s.get(PlannedWorkout, workout_id)
        if w and w.athlete_id == aid:
            w.status = status
            w.matched_activity_id = matched_activity_id
            s.add(w)


def get_workout(workout_id: int) -> dict | None:
    aid = current_athlete_id()
    with get_session() as s:
        w = s.get(PlannedWorkout, workout_id)
        return _workout_dict(w) if (w and w.athlete_id == aid) else None


def get_workouts(plan_id: int | None = None) -> list[dict]:
    aid = current_athlete_id()
    with get_session() as s:
        if plan_id is None:
            plan_id = s.exec(
                select(Plan.id).where(Plan.athlete_id == aid, Plan.status == "active").order_by(Plan.id.desc())
            ).first()
            if plan_id is None:
                return []
        rows = s.exec(
            select(PlannedWorkout)
            .where(PlannedWorkout.athlete_id == aid, PlannedWorkout.plan_id == plan_id)
            .order_by(PlannedWorkout.date)
        ).all()
        return [_workout_dict(w) for w in rows]


# ── helpers ───────────────────────────────────────────────────────────────────


def _plan_dict(p: Plan) -> dict:
    d = p.model_dump()
    d["weeks"] = json.loads(d.pop("weeks_json") or "[]")
    return d


def _workout_dict(w: PlannedWorkout) -> dict:
    d = w.model_dump()
    d["steps"] = json.loads(d.pop("structure_json") or "[]")
    return d


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()
