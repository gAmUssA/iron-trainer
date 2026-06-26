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
