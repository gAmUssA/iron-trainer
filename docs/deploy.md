# Deploying Iron Trainer

The app is a single container: FastAPI serves both the API and the built React
app. It runs identically on your laptop (SQLite, no login) and in production
(Postgres + Login with Strava) — switched entirely by environment variables, no
code changes. The recommended production target is **Railway + Supabase** (below).

## Local (Docker)

```bash
cp .env.example .env   # fill in STRAVA_* and ANTHROPIC_API_KEY
docker compose up --build
# open http://localhost:8000
```

Data persists in the `iron_data` Docker volume. Back it up by copying
`/data/iron_trainer.db` out of the volume.

## Fly.io (≈ free for personal use)

```bash
fly launch --no-deploy            # generates fly.toml; pick a name/region
fly volumes create iron_data --size 1 --region <your-region>
```

Add to `fly.toml`:

```toml
[mounts]
  source = "iron_data"
  destination = "/data"

[env]
  DATA_DIR = "/data"
  CORS_ORIGINS = "https://<your-app>.fly.dev"
  STRAVA_REDIRECT_URI = "https://<your-app>.fly.dev/api/strava/callback"
```

Set secrets (never bake them into the image):

```bash
fly secrets set STRAVA_CLIENT_ID=... STRAVA_CLIENT_SECRET=... ANTHROPIC_API_KEY=...
fly deploy
```

Then update your **Strava API app** "Authorization Callback Domain" to
`<your-app>.fly.dev`.

## Railway + Supabase (recommended production)

The container is **stateless** (data lives in Postgres; workout files are
generated in-memory and streamed), so no volume is needed — just point it at a
Supabase database.

### 1. Supabase (database)

1. Create a Supabase project; wait for it to provision.
2. **Connect → Session pooler** connection string. Use the *session pooler*
   (port 5432, host `…pooler.supabase.com`, user `postgres.<project-ref>`): it's
   **IPv4** (Railway egress is IPv4; the direct connection is IPv6-only) and
   supports the prepared statements + DDL that the app and Alembic use.
3. Form the URL for Railway (swap the driver to `psycopg`, keep SSL):

   ```
   DATABASE_URL=postgresql+psycopg://postgres.<ref>:<password>@aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require
   ```

No manual migration step — the app runs `alembic upgrade head` on startup.

### 2. Railway (app)

1. **New Project → Deploy from GitHub repo.** Railway uses `railway.toml` +
   the `Dockerfile`; it auto-redeploys on every push to the linked branch.
2. Set service **Variables** (no `/data` volume needed):

   ```
   DATABASE_URL=postgresql+psycopg://…pooler.supabase.com:5432/postgres?sslmode=require
   STRAVA_CLIENT_ID=…
   STRAVA_CLIENT_SECRET=…
   ANTHROPIC_API_KEY=…
   AUTH_REQUIRED=true
   SESSION_SECRET=<long random string>
   ALLOWED_STRAVA_IDS=33050502           # your athletes' Strava ids
   COOKIE_SECURE=true
   CORS_ORIGINS=https://<app>.up.railway.app
   STRAVA_REDIRECT_URI=https://<app>.up.railway.app/api/strava/callback
   ```

3. Generate the Railway domain, then set `CORS_ORIGINS` + `STRAVA_REDIRECT_URI`
   to it and update the **Strava API app** "Authorization Callback Domain" to
   `<app>.up.railway.app`.
4. Railway health-checks `/api/health` (from `railway.toml`).

### What's automated vs one-time

| Automated | One-time setup |
|---|---|
| **DB migrations** — `alembic upgrade head` on every startup | Create the Supabase project + copy its connection string |
| **Continuous deploy** — Railway redeploys on `git push` | Set Railway env vars (above) |
| **CI** — GitHub Actions runs tests (SQLite + Postgres) + lint + frontend build on push/PR | Point the Strava app's callback domain at the Railway URL |
| **Strava token refresh** — handled at runtime | Add each athlete's Strava id to `ALLOWED_STRAVA_IDS` |

Possible future automation: Railway **PR preview environments**, a scheduled
**race-catalog refresh**, and Dependabot for dependency bumps.

## Database: SQLite (default) or Postgres/Supabase

The backend is swappable by connection URL (SQLAlchemy/SQLModel + Alembic).

- **Local/dev (default):** leave `DATABASE_URL` empty → SQLite file under
  `DATA_DIR`. Nothing to set up.
- **Postgres / Supabase:** set `DATABASE_URL` to a SQLAlchemy URL. Schema is
  created/upgraded by Alembic; the app runs `alembic upgrade head` automatically
  on startup (`init_db()`), so a fresh Postgres comes up empty-then-migrated with
  no manual step. You can also run it by hand: `cd backend && uv run alembic
  upgrade head`.

```bash
# Supabase: use the POOLED connection (pgBouncer, port 6543) + SSL.
DATABASE_URL=postgresql+psycopg://postgres.<ref>:<password>@<region>.pooler.supabase.com:6543/postgres?sslmode=require
```

Notes:
- Deploys **start fresh** on Postgres — connect Strava and run a full backfill on
  the new DB (no SQLite→Postgres data copy). The de-dup/device-name work done
  locally re-runs there.
- Supabase's auth/realtime/storage aren't used — it's just managed Postgres here.
- Strava ids are stored as `BIGINT` (they exceed 32-bit `int`); dates/JSON stay
  as text for cross-DB parity. Schema changes go through Alembic
  (`uv run alembic revision --autogenerate -m "…"` → review → `upgrade head`).
- An existing **local SQLite** DB created before this change is `stamp`ed to the
  initial revision on first startup (data preserved, not rebuilt).

## Notes

- The single Anthropic call per plan generation keeps cost negligible; weekly
  `replan-week` calls are likewise cheap.
- Keep the box private to you — it holds your Strava tokens and training data.
- Run the test suite against Postgres anytime with
  `TEST_DATABASE_URL=postgresql+psycopg://… uv run pytest` (proves dialect parity).

## Race selection

Each athlete picks their race in **Thresholds → Race** from a bundled IRONMAN
catalog (70.3 + full, H2 2026, `app/data/races_h2_2026.json`) or enters a custom
race. The selection lives on the athlete row; the countdown, cut-offs (derived
from distance: 70.3 = 1:10/5:30/8:30, full = 2:20/10:30/17:00) and the finish
projection (leg distances 70.3 vs 140.6) all follow it. Env `RACE_*`/`CUTOFF_*`
remain the fallback until a race is picked. **Refresh the catalog** by editing the
JSON (dates are best-effort — verify each official athlete guide) and restarting;
`seed_races` upserts by slug.

## Multi-user (Login with Strava)

The app is **multi-tenant**. Locally it stays single-user with no login
(`AUTH_REQUIRED=false`, the default) — the existing behaviour. To run it as a
hosted multi-user app:

```bash
AUTH_REQUIRED=true
SESSION_SECRET=<a long random string>          # signs the session cookie
ALLOWED_STRAVA_IDS=33050502,12345678           # who may log in (empty = anyone)
```

- **Auth = Login with Strava.** The OAuth callback resolves the user by their
  Strava athlete id (find-or-create), stores tokens on that athlete row, and sets
  a signed-cookie session. Each user's activities / plan / metrics are isolated by
  `athlete_id`; the `race` catalog is shared.
- **Allowlist** (`ALLOWED_STRAVA_IDS`) gates who can register — keep it set for a
  private deployment. Empty allows anyone (logged with a startup warning).
- **Cookies:** the frontend sends `credentials: include`; CORS already allows
  credentials for the configured origin. Behind TLS, set `COOKIE_SECURE=true`
  (marks the session cookie `Secure`) and serve same-origin so the cookie flows.
- **Strava API rate limits are per app** (shared across all users) — another
  reason to keep the allowlist tight.
- **Existing single-user data** is owned by `athlete_id=1`; that row already holds
  your Strava id, so your first login attaches to it (no data migration needed).

Local development is unchanged: leave `AUTH_REQUIRED=false` and the app runs as
athlete 1 with no login.
