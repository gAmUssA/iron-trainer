import os

import httpx
import pytest


@pytest.fixture()
def proxy_stub(monkeypatch):
    """Factory that replaces strangler.fetch with a recording stub and returns
    the record dict (url/authz seen by the proxy). Shared by the strangler +
    export-proxy suites so the stub shape lives in one place."""
    from app import strangler

    def _make(*, content=b'{"schema_version": 1}', status=200, ct="application/json"):
        rec: dict = {}

        async def fake(url, authz):
            rec["url"] = url
            rec["authz"] = authz
            return httpx.Response(status, content=content, headers={"content-type": ct})

        monkeypatch.setattr(strangler, "fetch", fake)
        return rec

    return _make


@pytest.fixture()
def block_proxy(monkeypatch):
    """Make any proxy call fail loudly — for asserting a request stays local."""
    from app import strangler

    def _install(exc: Exception | None = None):
        async def boom(*a, **k):
            if exc is not None:
                raise exc
            raise AssertionError("proxy called when it must serve locally")

        monkeypatch.setattr(strangler, "fetch", boom)

    return _install


def pytest_addoption(parser):
    parser.addoption(
        "--pg", action="store_true", default=False,
        help="Run the suite against a throwaway Postgres (testcontainers, needs Docker).",
    )


@pytest.fixture(scope="session", autouse=True)
def _postgres_container(request):
    """With --pg, spin up a Postgres container and expose it as TEST_DATABASE_URL
    (which isolated_db already honors). Without it, a no-op → SQLite default."""
    if not request.config.getoption("--pg"):
        yield None
        return
    from testcontainers.postgres import PostgresContainer

    with PostgresContainer("postgres:16", driver="psycopg") as pg:
        os.environ["TEST_DATABASE_URL"] = pg.get_connection_url()
        try:
            yield pg
        finally:
            os.environ.pop("TEST_DATABASE_URL", None)


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
