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
from .logging_config import get_logger, setup_logging
from .routers import (
    analytics_router,
    athlete_router,
    auth_router,
    export_router,
    nutrition_router,
    plan_router,
    races_router,
    strava_router,
    tests_router,
)

log = get_logger("app")


def enforce_secure_config(s) -> None:
    """Refuse to serve multi-user auth with the known default session secret —
    session cookies are signed with it and carry the athlete id, so anyone could
    forge a session for any athlete. Unlike a DB failure at startup, this must
    NOT serve: fail the deploy loudly."""
    if s.auth_required and s.session_secret == "dev-insecure-change-me":
        raise RuntimeError(
            "AUTH_REQUIRED=true but SESSION_SECRET is the insecure default. "
            "Set a strong SESSION_SECRET before enabling multi-user auth."
        )


@asynccontextmanager
async def lifespan(app: FastAPI):
    setup_logging()
    s = get_settings()
    enforce_secure_config(s)
    log.info(
        "Iron Trainer %s starting — db=%s, auth_required=%s, allowlist=%d id(s)",
        __version__,
        "postgres" if not s.is_sqlite else "sqlite",
        s.auth_required,
        len(s.allowed_strava_id_set),
    )
    # Don't let a DB/migration error crash-loop the whole deploy: log it and still
    # start, so the liveness probe passes and /api/health?deep=1 + logs reveal it.
    try:
        init_db()
        log.info("Database ready (migrations applied).")
    except Exception:
        import traceback

        log.error("Startup init_db() failed — serving anyway; check DATABASE_URL.\n%s",
                  traceback.format_exc())
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
app.include_router(nutrition_router.router)
app.include_router(export_router.router)
app.include_router(races_router.router)
app.include_router(auth_router.router)
app.include_router(tests_router.router)


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
        except Exception:  # noqa: BLE001 - report any connectivity failure
            import traceback

            # Full error to the logs only — DB errors can echo the DSN's
            # host/user/db name and this endpoint is unauthenticated.
            log.error("Deep health check failed:\n%s", traceback.format_exc())
            out["status"] = "degraded"
            out["database"] = "error"
            out["detail"] = "database unreachable — see server logs"
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
