# Deploying Iron Trainer

The app is a single container built from **`backend-v2/Dockerfile`** (repo-root build
context): Quarkus (GraalVM native) serves both the API and the built React SPA on
`:8080`. Production runs on **Railway + Supabase (Postgres)**.

> The original Python/FastAPI backend was decommissioned 2026-07-21 (bean `foi1`).
> backend-v2 (Quarkus) is the source of truth.

## Local dev

No container needed — Quarkus dev mode starts a throwaway Postgres via **Dev Services**:

```bash
cd backend-v2
./mvnw quarkus:dev          # :8080, Dev UI at /q/dev/
```

Run the SPA against it in a second terminal (`cd frontend && npm run dev`, proxies
`/api` → `:8080`). See [`backend-v2/README.md`](../backend-v2/README.md) for more.

## Production — Railway + Supabase

The container is **stateless** (all data lives in Postgres; workout files are generated
in-memory and streamed), so no volume is needed.

### 1. Supabase (database)

1. Create a Supabase project.
2. Use the **Session pooler** connection (port 5432, host `…pooler.supabase.com`, user
   `postgres.<project-ref>`) — it's **IPv4** (Railway egress is IPv4; the direct
   connection is IPv6-only) and supports the prepared statements + DDL the app uses.
3. backend-v2 reads the database as three separate variables (not one URL):

   ```
   DATABASE_JDBC_URL=jdbc:postgresql://<host>.pooler.supabase.com:5432/postgres?sslmode=require
   DATABASE_USERNAME=postgres.<project-ref>
   DATABASE_PASSWORD=<password>
   ```

### 2. ⚠️ Migrations are NOT auto-applied in production

Flyway `migrate-at-start` is enabled only under `%dev`/`%test`. **In production the app
does not run migrations at startup** (bean `backend-v2-railway-deploy`). Any new
column/table must be applied to Supabase **manually, before** the image that expects
it cuts over — otherwise the new deploy 500s on the changed schema:

```bash
psql "<supabase-connection-string>" -c "ALTER TABLE … ADD COLUMN IF NOT EXISTS …;"
```

### 3. Railway (app service)

1. **New Project → Deploy from GitHub repo.**
2. Service settings:
   - **Root Directory:** `/` (repo root — the Docker build needs `frontend/` in context
     to compile the SPA into the image).
   - **Config File:** `backend-v2/railway.toml` (so the service does **not** read the
     root `railway.toml`, which is only the retired FastAPI service's never-build
     sentinel). It builds via `backend-v2/Dockerfile` and redeploys on pushes touching
     `backend-v2/**` or `frontend/**`.
3. **Variables:**

   ```
   DATABASE_JDBC_URL=…   DATABASE_USERNAME=…   DATABASE_PASSWORD=…
   SESSION_SECRET=<long random string>
   STRAVA_CLIENT_ID=…    STRAVA_CLIENT_SECRET=…
   STRAVA_REDIRECT_URI=https://<app>.up.railway.app/api/strava/callback
   ANTHROPIC_API_KEY=…
   ALLOWED_STRAVA_IDS=<comma-separated Strava athlete ids>   # login allowlist
   CORS_ORIGINS=https://<app>.up.railway.app
   ```

4. Health check path: `/q/health` (also `/api/health`).
5. Point your **Strava API app** "Authorization Callback Domain" at
   `<app>.up.railway.app`.

### After every backend-v2 (or frontend) merge — verify the deploy

Railway deploys can **fail silently**: a bad build leaves the previous image serving,
so CI-green ≠ deploy-healthy (bean `backend-v2-railway-deploy`). Confirm the front door
after each merge:

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://<app>.up.railway.app/api/health   # expect 200
# For a frontend change, also confirm the served SPA bundle hash actually changed:
curl -s https://<app>.up.railway.app/ | grep -oE 'assets/index-[A-Za-z0-9_]+\.js'
```

## Auth & multi-user

Login is **Sign in with Strava** (OAuth). The callback resolves the user by their Strava
athlete id (find-or-create), stores tokens on that athlete row, and sets a signed-cookie
session (`SESSION_SECRET`). Each user's activities / plan / metrics are isolated by
`athlete_id`; the race catalog is shared.

- **`ALLOWED_STRAVA_IDS`** gates who may register (comma-separated; empty = anyone).
  Keep it set for a private deployment. This is *in addition to* Strava's own per-app
  connected-athlete cap — see [Strava API compliance](strava-compliance.md).
- **Strava API rate limits are per app** (shared across users) — another reason to keep
  the allowlist tight.
- A native **Sign in with Apple** path (Strava-free accounts) is planned — bean
  `iron-trainer-3e6w`.
