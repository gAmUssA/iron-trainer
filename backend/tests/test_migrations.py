"""Validate the Alembic migration path (separate from the fast create_all used
by the rest of the suite)."""

from sqlalchemy import inspect

EXPECTED = {"athlete", "activities", "plan", "planned_workouts", "metrics_daily", "alembic_version"}


def test_alembic_upgrade_head_builds_full_schema(tmp_path, monkeypatch):
    # Use a fresh DATA_DIR so this DB is empty (the autouse fixture's DB is elsewhere).
    monkeypatch.setenv("DATA_DIR", str(tmp_path / "mig"))
    monkeypatch.delenv("DATABASE_URL", raising=False)

    from app.config import get_settings

    get_settings.cache_clear()
    from alembic import command

    from app import db

    db.dispose_engine()
    command.upgrade(db._alembic_config(), "head")

    names = set(inspect(db.get_engine()).get_table_names())
    assert EXPECTED <= names

    db.dispose_engine()
    get_settings.cache_clear()


def test_init_db_upgrades_a_legacy_initial_schema(tmp_path, monkeypatch):
    """A legacy pre-Alembic DB (initial schema, no alembic_version) is stamped to
    the base revision and upgraded — so later migrations (e.g. the race tables)
    are applied to it rather than skipped."""
    monkeypatch.setenv("DATA_DIR", str(tmp_path / "pre"))
    monkeypatch.delenv("DATABASE_URL", raising=False)

    from app.config import get_settings

    get_settings.cache_clear()
    import sqlalchemy as sa
    from alembic import command
    from alembic.script import ScriptDirectory

    from app import db

    db.dispose_engine()
    cfg = db._alembic_config()
    base = ScriptDirectory.from_config(cfg).get_base()
    command.upgrade(cfg, base)  # build ONLY the initial schema (no race table)
    eng = db.get_engine()
    assert "race" not in set(inspect(eng).get_table_names())
    with eng.begin() as c:  # simulate pre-Alembic: remove the version marker
        c.execute(sa.text("DROP TABLE alembic_version"))

    db.dispose_engine()
    db.init_db()  # stamp base + upgrade head → race tables get added

    names = set(inspect(db.get_engine()).get_table_names())
    assert "race" in names and "alembic_version" in names

    db.dispose_engine()
    get_settings.cache_clear()
