"""SQLModel table definitions — the single source of truth for the schema
(used by both the engine and Alembic autogenerate).

Design notes:
- Mirrors the original hand-written SQLite schema column-for-column so an
  existing local SQLite DB stays compatible (Alembic can `stamp` it).
- Strava ids (activity id, athlete id, token expiry, fk references) use
  BigInteger — they exceed Postgres' 32-bit INTEGER range.
- Dates/timestamps are stored as ISO **strings** (preserves the lexical
  range/`<` comparisons the repo relies on); JSON blobs stay TEXT and are
  (de)serialised in repo.py — both kept identical across SQLite and Postgres.
"""

from __future__ import annotations

from sqlalchemy import BigInteger, Column
from sqlmodel import Field, SQLModel


class Race(SQLModel, table=True):
    """Catalog of IRONMAN events (global, not owned by an athlete)."""

    __tablename__ = "race"

    id: int | None = Field(default=None, primary_key=True)
    slug: str = Field(index=True, unique=True)
    name: str
    date: str  # ISO YYYY-MM-DD
    distance: str  # "70.3" | "140.6"
    city: str | None = None
    country: str | None = None
    cutoff_swim_s: int | None = None
    cutoff_bike_s: int | None = None
    cutoff_finish_s: int | None = None


class Athlete(SQLModel, table=True):
    __tablename__ = "athlete"

    id: int | None = Field(default=None, primary_key=True)  # autoincrement per user
    strava_athlete_id: int | None = Field(default=None, sa_type=BigInteger, index=True, unique=True)
    name: str | None = None
    strava_access_token: str | None = None
    strava_refresh_token: str | None = None
    strava_token_expires_at: int | None = Field(default=None, sa_type=BigInteger)
    # Thresholds (user-editable; seeded from Strava analysis).
    ftp: float | None = None
    threshold_hr: int | None = None
    max_hr: int | None = None
    threshold_pace_run: float | None = None
    css_swim: float | None = None
    weekly_hours_target: float | None = None
    # Selected race (denormalised effective race; becomes per-user with multi-user).
    race_id: int | None = Field(default=None, foreign_key="race.id", ondelete="SET NULL")
    race_name: str | None = None
    race_date: str | None = None  # ISO YYYY-MM-DD
    race_distance: str | None = None
    cutoff_swim_s: int | None = None
    cutoff_bike_s: int | None = None
    cutoff_finish_s: int | None = None
    updated_at: str | None = None


class Activity(SQLModel, table=True):
    __tablename__ = "activities"

    # Strava activity id (explicit, not auto-assigned).
    id: int = Field(sa_column=Column(BigInteger, primary_key=True, autoincrement=False))
    athlete_id: int = Field(foreign_key="athlete.id", ondelete="CASCADE", index=True)
    sport: str = Field(index=True)
    start_date: str = Field(index=True)
    name: str | None = None
    moving_time: int | None = None
    elapsed_time: int | None = None
    distance: float | None = None
    avg_power: float | None = None
    weighted_power: float | None = None
    avg_hr: float | None = None
    max_hr: float | None = None
    avg_speed: float | None = None
    elevation_gain: float | None = None
    has_power_meter: int | None = 0
    tss: float | None = None
    intensity_factor: float | None = None
    tss_method: str | None = None
    device_name: str | None = None
    is_duplicate: int | None = 0
    primary_id: int | None = Field(default=None, sa_type=BigInteger)
    raw_json: str | None = None
    created_at: str | None = None


class Plan(SQLModel, table=True):
    __tablename__ = "plan"

    id: int | None = Field(default=None, primary_key=True)
    athlete_id: int = Field(foreign_key="athlete.id", ondelete="CASCADE", index=True)
    race_name: str | None = None
    race_date: str | None = None
    status: str | None = "active"
    summary: str | None = None
    weeks_json: str | None = None
    created_at: str | None = None


class PlannedWorkout(SQLModel, table=True):
    __tablename__ = "planned_workouts"

    id: int | None = Field(default=None, primary_key=True)
    athlete_id: int = Field(foreign_key="athlete.id", ondelete="CASCADE", index=True)
    plan_id: int | None = Field(default=None, foreign_key="plan.id", ondelete="CASCADE", index=True)
    date: str = Field(index=True)
    sport: str
    title: str | None = None
    description: str | None = None
    structure_json: str | None = None
    duration_s: int | None = None
    distance_m: float | None = None
    planned_tss: float | None = None
    intensity: str | None = None
    fit_path: str | None = None
    zwo_path: str | None = None
    status: str | None = "planned"
    matched_activity_id: int | None = Field(default=None, sa_type=BigInteger)
    created_at: str | None = None


class MetricDaily(SQLModel, table=True):
    __tablename__ = "metrics_daily"

    athlete_id: int = Field(foreign_key="athlete.id", ondelete="CASCADE", primary_key=True)
    date: str = Field(primary_key=True)
    tss: float | None = 0
    ctl: float | None = None
    atl: float | None = None
    tsb: float | None = None


class DeviceToken(SQLModel, table=True):
    """A paired native client (e.g. the iOS app).

    Lifecycle: a logged-in athlete mints a row with a short-lived ``pairing_code``
    (token_hash null). The app exchanges the code for a bearer token; we store only
    its sha256 in ``token_hash`` and clear the code. Requests then authenticate with
    ``Authorization: Bearer <token>``.
    """

    __tablename__ = "device_token"

    id: int | None = Field(default=None, primary_key=True)
    athlete_id: int = Field(foreign_key="athlete.id", ondelete="CASCADE", index=True)
    name: str | None = None  # device label
    pairing_code: str | None = Field(default=None, index=True)  # cleared once claimed
    pairing_expires_at: int | None = Field(default=None, sa_type=BigInteger)  # epoch s
    token_hash: str | None = Field(default=None, index=True, unique=True)  # sha256 hex
    created_at: str | None = None
    last_used_at: str | None = None


class FitnessTestResult(SQLModel, table=True):
    """A recorded fitness-test result: the raw inputs an athlete entered and the
    thresholds computed from them. Applying it writes those thresholds to the
    athlete profile (see repo.apply_test_result)."""

    __tablename__ = "fitness_test_result"

    id: int | None = Field(default=None, primary_key=True)
    athlete_id: int = Field(foreign_key="athlete.id", ondelete="CASCADE", index=True)
    test_slug: str = Field(index=True)
    sport: str
    date: str  # ISO YYYY-MM-DD (when the test was performed)
    inputs_json: str | None = None   # raw entry, e.g. {"avg_power_w": 250}
    result_json: str | None = None   # computed thresholds, e.g. {"ftp": 238}
    applied: bool = False
    created_at: str | None = None
