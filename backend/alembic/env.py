"""Alembic environment — targets the SQLModel metadata and pulls the database
URL from the application settings, so the same migrations run against SQLite or
Postgres.

⚠️ FROZEN (2026-07-20): the schema is now owned by backend-v2 (Quarkus) Flyway.
Do NOT add new Alembic revisions — see FROZEN.md in this directory. (No code
guard here on purpose: init_db() imports this on FastAPI startup, so failing
here would break the rollback path.)"""

from __future__ import annotations

from logging.config import fileConfig

from alembic import context
from sqlmodel import SQLModel

from app import models  # noqa: F401 — register tables on SQLModel.metadata
from app.config import get_settings
from app.db import get_engine

config = context.config
# init_db() runs this in-process on startup; the default fileConfig would disable
# all existing loggers (the app's "iron" logger, uvicorn's), silencing every log
# after the first migration. Keep them alive.
if config.config_file_name is not None:
    fileConfig(config.config_file_name, disable_existing_loggers=False)

target_metadata = SQLModel.metadata


def run_migrations_offline() -> None:
    url = get_settings().effective_database_url
    context.configure(
        url=url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        render_as_batch=url.startswith("sqlite"),
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    engine = get_engine()
    with engine.connect() as connection:
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
            render_as_batch=connection.dialect.name == "sqlite",
        )
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
