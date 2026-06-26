"""Database engine, session, and schema bootstrap (SQLite or Postgres).

The engine is built from ``settings.effective_database_url`` so the backend is
swappable by connection URL: empty/`sqlite:///…` for local-first dev, a
`postgresql+psycopg://…` URL for Postgres/Supabase. Schema is managed by Alembic.
"""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from functools import lru_cache
from pathlib import Path

from sqlalchemy import Engine, event, inspect
from sqlmodel import Session, create_engine, select

from . import models  # noqa: F401 — ensure tables are registered on SQLModel.metadata
from .config import get_settings
from .models import Athlete

# Alembic config lives next to the app package: backend/alembic.
_BACKEND_DIR = Path(__file__).resolve().parents[1]
_CORE_TABLES = ("athlete", "activities", "plan", "planned_workouts", "metrics_daily")


@lru_cache
def get_engine() -> Engine:
    settings = get_settings()
    url = settings.effective_database_url
    if url.startswith("sqlite"):
        connect_args = {"check_same_thread": False}
    else:
        # Fail fast on an unreachable DB instead of hanging the startup window.
        connect_args = {"connect_timeout": 10}
    engine = create_engine(url, connect_args=connect_args, pool_pre_ping=True)

    if settings.is_sqlite:
        @event.listens_for(engine, "connect")
        def _sqlite_pragmas(dbapi_conn, _record):  # pragma: no cover - trivial
            cur = dbapi_conn.cursor()
            cur.execute("PRAGMA foreign_keys=ON")
            cur.execute("PRAGMA journal_mode=WAL")
            cur.close()

    return engine


def dispose_engine() -> None:
    """Drop the cached engine (used by tests switching databases)."""
    if get_engine.cache_info().currsize:
        get_engine().dispose()
    get_engine.cache_clear()


@contextmanager
def get_session() -> Iterator[Session]:
    session = Session(get_engine())
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()


def _alembic_config():
    from alembic.config import Config

    cfg = Config(str(_BACKEND_DIR / "alembic.ini"))
    cfg.set_main_option("script_location", str(_BACKEND_DIR / "alembic"))
    cfg.set_main_option("sqlalchemy.url", get_settings().effective_database_url)
    return cfg


def init_db() -> None:
    """Bring the schema to head and ensure the single athlete row.

    Fresh DB → ``alembic upgrade head``. An existing pre-Alembic SQLite DB (core
    tables present, no ``alembic_version``) is ``stamp``ed to head so its data is
    preserved rather than recreated.
    """
    from alembic import command

    get_settings().ensure_dirs()
    engine = get_engine()
    cfg = _alembic_config()

    names = set(inspect(engine).get_table_names())
    pre_alembic = "alembic_version" not in names and all(t in names for t in _CORE_TABLES)
    if pre_alembic:
        # Existing pre-Alembic schema matches the INITIAL revision — stamp that
        # (not head), so the remaining migrations still run to bring it current.
        from alembic.script import ScriptDirectory

        command.stamp(cfg, ScriptDirectory.from_config(cfg).get_base())
    command.upgrade(cfg, "head")

    with get_session() as session:
        # Seed the default local athlete on a fresh DB. Insert WITHOUT an explicit
        # id so the autoincrement/identity sequence advances (a forced id=1 leaves
        # Postgres' sequence at 1 and the next user collides). First row → id 1.
        if session.exec(select(Athlete)).first() is None:
            session.add(Athlete())

    _seed_races()


def _seed_races() -> None:
    """Load the bundled IRONMAN catalog and upsert it (idempotent)."""
    import json

    from . import repo  # local import avoids a circular import at module load

    data_file = Path(__file__).parent / "data" / "races_h2_2026.json"
    try:
        payload = json.loads(data_file.read_text())
    except FileNotFoundError:
        return
    repo.seed_races(payload.get("races", []))
