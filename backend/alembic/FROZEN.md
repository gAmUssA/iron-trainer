# ⚠️ Alembic is FROZEN (2026-07-20)

The database schema is now owned by **backend-v2 (Quarkus)** via **Flyway**
(`backend-v2/src/main/resources/db/migration`). The FastAPI backend has been
decommissioned — backend-v2 is the front door at `iron-trainer.up.railway.app`
(strangler migration complete; bean `iron-trainer-foi1`).

## Do NOT add new Alembic revisions

The 10 existing revisions in `versions/` are the schema's history up to the
cutover and must stay as-is (a revived FastAPI applies them on startup via
`init_db()`, which is a safe no-op once the DB is at head). **All new schema
changes go through backend-v2 Flyway migrations, not here.** Adding an Alembic
revision now would diverge the two migration histories against the same shared
Postgres and cause conflicts.

There is deliberately **no code guard** in `env.py`: `init_db()` imports it on
FastAPI startup, so a hard failure there would break the rollback path. The
freeze is a convention — enforced by review, not by code.

## Rollback (if backend-v2 must be reverted to FastAPI)

FastAPI's Railway service is kept STOPPED, not deleted, so its variables
(incl. `SESSION_SECRET`, referenced by backend-v2) still resolve. To restore:
1. Remove the decommission `watchPatterns` sentinel in the root `railway.toml`
   (or `railway up` the service) so it deploys again.
2. Swap the `iron-trainer.up.railway.app` domain back from backend-v2 to this
   service (see `iron-trainer-foi1`).
