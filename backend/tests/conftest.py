import os

import pytest


@pytest.fixture(autouse=True)
def isolated_db(tmp_path, monkeypatch):
    """Give every test a clean schema.

    Default: a throwaway SQLite DB per test (fast, == migration head). Set
    TEST_DATABASE_URL (e.g. a Postgres URL) to run the whole suite against that
    backend instead — the schema is dropped + recreated per test for isolation.
    Either way the dedicated migration test exercises `alembic upgrade head`.
    """
    test_url = os.environ.get("TEST_DATABASE_URL")
    monkeypatch.setenv("DATA_DIR", str(tmp_path))
    if test_url:
        monkeypatch.setenv("DATABASE_URL", test_url)
    else:
        monkeypatch.delenv("DATABASE_URL", raising=False)

    from app.config import get_settings

    get_settings.cache_clear()

    from sqlmodel import SQLModel

    from app import db
    from app.models import Athlete

    db.dispose_engine()
    engine = db.get_engine()
    if test_url:
        SQLModel.metadata.drop_all(engine)  # clean slate on the shared DB
    SQLModel.metadata.create_all(engine)
    # Mark the create_all schema as fully migrated so a later init_db() (via the
    # TestClient lifespan) treats it as current rather than pre-Alembic.
    from alembic import command

    command.stamp(db._alembic_config(), "head")
    with db.get_session() as s:
        from sqlmodel import select

        if s.exec(select(Athlete)).first() is None:
            s.add(Athlete())  # autoincrement → id 1 (no forced id; keeps PG sequence sane)

    yield

    db.dispose_engine()
    get_settings.cache_clear()
