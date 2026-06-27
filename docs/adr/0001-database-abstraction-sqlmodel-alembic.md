# 0001 — Database abstraction: SQLModel + Alembic, SQLite local / Postgres prod

- **Status:** Accepted (retroactive)
- **Date:** 2026-06 (greenfield build)
- **Deciders:** Viktor + Claude

## Context

Iron Trainer is local-first: it must run with zero setup on a laptop, but also
deploy to a cheap cloud host with durable, shared storage. SQLite is perfect
locally (a single file, no server); Postgres is the right fit in production
(managed, concurrent, survives stateless container restarts). We needed **one
codebase, one schema definition** that runs on both without per-dialect branches.

## Decision

Use **SQLModel** (SQLAlchemy 2.0 core) as the single source of truth for the
schema, with **Alembic** for migrations. The engine is selected by a `DATABASE_URL`
setting: empty → SQLite file under `DATA_DIR`; otherwise a SQLAlchemy URL (Postgres
via `psycopg` v3). `init_db()` runs `alembic upgrade head` on startup so a fresh
database comes up migrated with no manual step.

Cross-dialect rules baked in:
- Strava ids (activity id, athlete id, token expiry) use `BigInteger` (exceed
  Postgres 32-bit `INTEGER`).
- Dates/timestamps stored as ISO **strings**, JSON blobs as TEXT — identical
  lexical ordering and behavior on both engines; (de)serialized in `repo.py`.
- The only dialect-specific code is the activity upsert (`ON CONFLICT`), which
  collapses duplicate ids in a batch (Postgres rejects affecting a row twice).
- SQLite migrations use `batch_alter_table` with **named** constraints (SQLite
  can't alter unnamed FKs).

## Alternatives considered

- **Raw SQL + hand-rolled migrations** — rejected: duplicated dialect handling,
  error-prone, no schema/model single source.
- **SQLite everywhere (incl. prod on a volume)** — rejected: ties prod to a single
  stateful node; the chosen Railway+Supabase target is stateless + managed PG.
- **Django ORM / Tortoise** — rejected: heavier than needed; SQLModel pairs the
  Pydantic models we already use for the API with SQLAlchemy.

## Consequences

- (+) Same code path local and prod; `uv run pytest` runs the suite on SQLite and,
  with `--pg` (testcontainers) or `TEST_DATABASE_URL`, on real Postgres — dialect
  parity is CI-enforced.
- (+) Stateless production container (data in Postgres) → trivial horizontal/redeploy.
- (−) Must stay disciplined about cross-dialect types (the string-dates / BigInteger
  rules above) and test both engines for schema changes.
- A subtle startup bug was found later: a legacy pre-Alembic SQLite DB must be
  `stamp`ed to the initial revision (not head) before upgrading, or migrations are
  skipped. Covered by a regression test.

## Implementation notes

`app/models.py` (tables), `app/db.py` (engine/`init_db`/stamp logic),
`app/config.py` (`effective_database_url`, normalizes bare `postgres://` →
`postgresql+psycopg://`), `alembic/`.

## Verification

`cd backend && uv run pytest` (SQLite) and `uv run pytest --pg` (Postgres);
`uv run alembic upgrade head` on a fresh DB of each dialect.
