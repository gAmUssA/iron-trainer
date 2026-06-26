from starlette.testclient import TestClient

from app.main import app


def test_health_shallow():
    with TestClient(app) as c:
        r = c.get("/api/health")
        assert r.status_code == 200
        assert r.json()["status"] == "ok"
        assert "database" not in r.json()  # shallow → no DB touch


def test_health_deep_reports_db_ok():
    with TestClient(app) as c:
        r = c.get("/api/health?deep=1")
        assert r.status_code == 200
        body = r.json()
        assert body["status"] == "ok"
        assert body["database"] == "ok"


def test_database_url_normalizes_to_psycopg():
    from app.config import Settings

    assert Settings(database_url="postgresql://u:p@h:5432/d").effective_database_url.startswith(
        "postgresql+psycopg://"
    )
    assert Settings(database_url="postgres://u:p@h:5432/d").effective_database_url.startswith(
        "postgresql+psycopg://"
    )
    # Already-qualified URLs are left alone; empty → SQLite.
    assert Settings(database_url="postgresql+psycopg://u:p@h/d").effective_database_url.startswith(
        "postgresql+psycopg://"
    )
    assert Settings(database_url="").effective_database_url.startswith("sqlite")
