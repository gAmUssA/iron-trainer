"""FastAPI application entry point."""

from __future__ import annotations

from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from sqlalchemy import text
from starlette.middleware.sessions import SessionMiddleware

from . import __version__, auth, repo
from .config import REPO_ROOT, get_settings
from .db import get_engine, init_db
from .routers import (
    analytics_router,
    athlete_router,
    auth_router,
    export_router,
    plan_router,
    races_router,
    strava_router,
)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Don't let a DB/migration error crash-loop the whole deploy: log it and still
    # start, so the liveness probe passes and /api/health?deep=1 + logs reveal it.
    try:
        init_db()
    except Exception:
        import logging
        import traceback

        logging.getLogger("uvicorn.error").error(
            "Startup init_db() failed — serving anyway; check DATABASE_URL.\n%s",
            traceback.format_exc(),
        )
    yield


app = FastAPI(title="Iron Trainer API", version=__version__, lifespan=lifespan)

settings = get_settings()
# Middleware executes outer→inner in reverse add-order, so the last added runs
# first. We want: CORS → Session → AuthContext → endpoint (auth reads the session).
app.add_middleware(auth.AuthContextMiddleware)
app.add_middleware(
    SessionMiddleware,
    secret_key=settings.session_secret,
    same_site="lax",
    https_only=settings.cookie_secure,
)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origin_list,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


app.include_router(strava_router.router)
app.include_router(athlete_router.router)
app.include_router(analytics_router.router)
app.include_router(plan_router.router)
app.include_router(export_router.router)
app.include_router(races_router.router)
app.include_router(auth_router.router)


@app.get("/api/health")
def health(response: Response, deep: bool = False) -> dict:
    """Liveness by default. `?deep=1` also checks DB connectivity (503 if down) —
    useful as a readiness probe to catch a bad DATABASE_URL early."""
    out = {"status": "ok", "version": __version__}
    if deep:
        try:
            with get_engine().connect() as conn:
                conn.execute(text("SELECT 1"))
            out["database"] = "ok"
        except Exception as e:  # noqa: BLE001 - report any connectivity failure
            out["status"] = "degraded"
            out["database"] = "error"
            out["detail"] = str(e)[:200]
            response.status_code = 503
    return out


@app.get("/api/status")
def status() -> dict:
    """Config & setup status used by the frontend to guide onboarding."""
    s = get_settings()
    authenticated = auth.maybe_current_athlete_id() is not None
    race = repo.effective_race() if authenticated else {"name": s.race_name, "date": s.race_date.isoformat(), "distance": None}
    return {
        "version": __version__,
        "race": {"name": race["name"], "date": race["date"], "distance": race.get("distance")},
        "strava_configured": s.strava_configured,
        "anthropic_configured": s.anthropic_configured,
        "auth_required": s.auth_required,
        "authenticated": authenticated,
    }


# In production (Docker), serve the built React app from FastAPI so the whole
# thing runs as a single container. In dev, the Vite server handles this.
_frontend_dist = Path(REPO_ROOT) / "frontend" / "dist"
if _frontend_dist.is_dir():
    app.mount("/", StaticFiles(directory=_frontend_dist, html=True), name="frontend")
