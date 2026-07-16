"""Application settings, loaded from environment / .env file."""

from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
from functools import lru_cache
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

# Repo root is two levels up from this file: backend/app/config.py -> repo/
REPO_ROOT = Path(__file__).resolve().parents[2]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=(REPO_ROOT / ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # Strava
    strava_client_id: str = ""
    strava_client_secret: str = ""
    strava_redirect_uri: str = "http://localhost:8000/api/strava/callback"

    # Claude
    anthropic_api_key: str = ""
    planner_model: str = "claude-sonnet-4-6"
    season_model: str = "claude-opus-4-8"

    # App
    data_dir: Path = Field(default=REPO_ROOT / "data")
    # Empty → local SQLite under data_dir. Set to a SQLAlchemy URL for Postgres,
    # e.g. postgresql+psycopg://user:pass@host:6543/postgres?sslmode=require
    database_url: str = ""
    race_name: str = "IRONMAN 70.3 New York"
    race_date: date = date(2026, 9, 26)
    cors_origins: str = "http://localhost:5173"

    # Multi-user auth. Off by default → local single-user (no login). Turn on for
    # a deployment: requires session_secret; allowed_strava_ids gates who can log in.
    auth_required: bool = False
    session_secret: str = "dev-insecure-change-me"
    allowed_strava_ids: str = ""  # comma-separated Strava athlete ids (empty = allow all)
    # Strangler flip: when set (e.g. http://backend-v2.railway.internal:8080),
    # bearer-authenticated requests for allowlisted paths (proxy_paths) are
    # proxied to backend-v2. Empty = serve locally (instant rollback).
    # Session-cookie traffic always stays local. (Name kept for env
    # back-compat; it's the backend-v2 base URL for every proxied vertical.)
    export_proxy_url: str = ""
    # Comma-separated allowlist of paths backend-v2 owns. A pattern ending in '*'
    # matches by prefix. Default = today's proxied export set, so enabling the
    # single-seam middleware changes nothing. Append a vertical's path once its
    # parity gate is green to flip that vertical's BEARER traffic — e.g. add
    # "/api/metrics/readiness/today" (parity-verified) to route iOS readiness to
    # the Quarkus binary. Web/cookie surfaces (zones, pmc) won't proxy regardless.
    proxy_paths: str = "/api/export/workout/*,/api/export/plan.itw"
    default_athlete_id: int = 1  # identity used in local no-login mode
    cookie_secure: bool = False  # set true behind HTTPS (Secure session cookie)
    log_level: str = "INFO"  # app log verbosity (DEBUG/INFO/WARNING/ERROR)

    # Ignore Strava activities older than this many years (keeps the DB lean and
    # the fitness curve relevant). Set to 0 to keep all history.
    history_years: int = 5

    # Standard IRONMAN 70.3 cut-offs (cumulative from individual start), in
    # seconds. Override per-race once the official athlete guide is published.
    cutoff_swim_s: int = 70 * 60  # 1:10:00 — out of the water
    cutoff_bike_s: int = 330 * 60  # 5:30:00 — cumulative through the bike (swim+T1+bike)
    cutoff_finish_s: int = 510 * 60  # 8:30:00 — overall finish

    @property
    def db_path(self) -> Path:
        return self.data_dir / "iron_trainer.db"

    @property
    def effective_database_url(self) -> str:
        """SQLAlchemy URL — explicit DATABASE_URL, else local SQLite file.

        Normalizes a bare Postgres URL (e.g. the raw Supabase / `postgres://`
        string) to the psycopg-v3 driver we ship, so the platform doesn't try
        the absent psycopg2.
        """
        url = self.database_url or f"sqlite:///{self.db_path}"
        if url.startswith("postgres://"):
            url = "postgresql+psycopg://" + url[len("postgres://") :]
        elif url.startswith("postgresql://"):
            url = "postgresql+psycopg://" + url[len("postgresql://") :]
        return url

    @property
    def is_sqlite(self) -> bool:
        return self.effective_database_url.startswith("sqlite")

    @property
    def exports_dir(self) -> Path:
        return self.data_dir / "exports"

    @property
    def cors_origin_list(self) -> list[str]:
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()]

    @property
    def proxy_path_list(self) -> list[str]:
        """Allowlist of paths backend-v2 owns (see proxy_paths / strangler.py)."""
        return [p.strip() for p in self.proxy_paths.split(",") if p.strip()]

    @property
    def allowed_strava_id_set(self) -> set[int]:
        out: set[int] = set()
        for tok in self.allowed_strava_ids.split(","):
            tok = tok.strip()
            if tok.isdigit():
                out.add(int(tok))
        return out

    @property
    def history_cutoff_date(self) -> date | None:
        if not self.history_years:
            return None
        return date.today() - timedelta(days=round(365.25 * self.history_years))

    @property
    def history_cutoff_epoch(self) -> int | None:
        d = self.history_cutoff_date
        if d is None:
            return None
        return int(datetime.combine(d, time(), tzinfo=timezone.utc).timestamp())

    def ensure_dirs(self) -> None:
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.exports_dir.mkdir(parents=True, exist_ok=True)

    @property
    def strava_configured(self) -> bool:
        return bool(self.strava_client_id and self.strava_client_secret)

    @property
    def anthropic_configured(self) -> bool:
        return bool(self.anthropic_api_key)


@lru_cache
def get_settings() -> Settings:
    settings = Settings()
    settings.ensure_dirs()
    return settings
