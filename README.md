# 🏊‍♂️🚴‍♂️🏃‍♂️ Iron Trainer

AI-adaptive triathlon training for **IRONMAN 70.3 New York — Sat Sep 26, 2026**.

Pulls your Strava history, computes training load (TSS / CTL / ATL / TSB), generates
an LLM-adaptive 70.3 plan (bounded by a safety validator), exports structured `.fit` /
`.zwo` workouts you import into **TrainingPeaks** (which syncs them to your Garmin/Wahoo),
and tracks progress in a local web dashboard. All data is stored locally in SQLite.

## Stack

- **Backend:** Python / FastAPI + SQLModel/SQLAlchemy + Alembic (`backend/`) —
  swappable DB: **SQLite** local-first by default, **Postgres/Supabase** via
  `DATABASE_URL`
- **Frontend:** React (Vite) + Recharts (`frontend/`), tabbed UI (Dashboard /
  Training Plan / Trends / Thresholds), dark theme — Space Grotesk + IBM Plex Mono
- **AI:** Claude API for plan generation
- **Integrations:** Strava API (read), TrainingPeaks / Garmin (file export)

## Quick start (local dev)

1. **Configure secrets**

   ```bash
   cp .env.example .env
   # Fill in STRAVA_CLIENT_ID / STRAVA_CLIENT_SECRET (https://www.strava.com/settings/api)
   # and ANTHROPIC_API_KEY (https://console.anthropic.com/)
   ```

   When creating the Strava app, set **Authorization Callback Domain** to `localhost`.

2. **Backend**

   ```bash
   cd backend
   uv venv --python 3.12
   uv pip install -e ".[dev]"
   uv run uvicorn app.main:app --reload --port 8000
   ```

3. **Frontend** (separate terminal)

   ```bash
   cd frontend
   npm install
   npm run dev          # http://localhost:5173 (proxies /api to :8000)
   ```

## Run as a single container

```bash
docker compose up --build       # http://localhost:8000
```

SQLite + generated workout files persist in the `iron_data` volume (`/data`).

## How it works

> First visit launches a **guided tour** (WalkthroughJS) of every screen. Replay
> it anytime with the **? Tour** button in the header.

1. **Connect Strava** (`Connect Strava` button) → OAuth, then **Full backfill**
   pulls your history. The app computes TSS per activity and your CTL/ATL/TSB.
   Activities recorded by multiple devices at once (e.g. Apple Watch **and** a
   bike computer) are **de-duplicated** — one is kept per event with a
   **sport-aware** source preference (cycling → Garmin Edge / power meter;
   swim & run → Apple Watch), then HR/power/length — so training load isn't
   double-counted.
2. **Thresholds** (FTP, run threshold pace, swim CSS, HR) are **inferred from your
   last ~12 weeks** and shown for you to edit — they drive TSS, zones and targets.
3. **Generate plan** → Claude adapts a validated 70.3 periodization (base → build →
   peak → 2-week taper to race day) to your real fitness. A deterministic
   **safety validator** caps the weekly ramp, inserts recovery weeks, and enforces
   the taper, reporting every adjustment.
4. **Export** → each session downloads as a structured `.fit` (and `.zwo` for bike),
   or grab a weekly / full-plan `.zip`. Import into **TrainingPeaks** (Workout
   Library → Import); with TP Premium they sync to your Garmin/Wahoo. The same
   `.fit` files load directly into Garmin Connect as a fallback.
5. **Track** progress on the dashboards: Performance Management Chart, weekly
   volume, per-sport trends, and a projected 70.3 finish time with **cut-off
   checks** (swim 1:10, bike 5:30 cumulative, finish 8:30 — the standard 70.3
   limits; verify against the official athlete guide and override in `.env`
   via `CUTOFF_SWIM_S` / `CUTOFF_BIKE_S` / `CUTOFF_FINISH_S`).

## Cheap cloud deploy (optional)

The same image deploys to Fly.io / Railway with a persistent volume at `/data`.
No code changes vs local. See [`docs/deploy.md`](docs/deploy.md).

## API reference (selected)

| Method   | Path                                                         | Purpose                                     |
|----------|--------------------------------------------------------------|---------------------------------------------|
| GET      | `/api/status`                                                | config / race / setup state                 |
| GET      | `/api/strava/connect` · `/callback`                          | OAuth                                       |
| POST     | `/api/strava/sync?full=true`                                 | backfill / incremental sync                 |
| GET/PUT  | `/api/athlete` · `/api/athlete/profile`                      | view / edit thresholds                      |
| GET      | `/api/metrics/pmc` · `/weekly` · `/trends` · `/readiness`    | dashboards                                  |
| POST/GET | `/api/plan/generate` · `/api/plan`                           | generate / fetch plan                       |
| POST     | `/api/plan/replan-week?week_start=YYYY-MM-DD`                | AI re-plan one week                         |
| POST     | `/api/strava/dedup`                                          | re-run de-duplication (one-click in the UI) |
| GET      | `/api/export/workout/{id}.fit` · `.zwo`                      | single workout file                         |
| GET      | `/api/export/week/{week_start}.zip` · `/api/export/plan.zip` | bundles                                     |

## Tasks (all via `uv`)

Run from the `backend/` directory. `DATA_DIR` selects the database (defaults to `./data`).

```bash
uv run uvicorn app.main:app --reload --port 8000   # dev API (with frontend proxy on :5173)
uv run iron-serve                                  # production-style serve (API + built UI)
uv run pytest                                      # test suite (SQLite, fast)
uv run pytest --pg                                 # same suite on a throwaway Postgres (testcontainers)
TEST_DATABASE_URL=postgresql+psycopg://… uv run pytest   # …or point at an existing Postgres
uv run ruff check app                              # lint
uv run alembic upgrade head                        # apply DB migrations (auto on startup too)

# Re-run activity de-duplication on the existing DB (no full re-sync):
uv run iron-dedup                                  # full: fetch device names (bike→Edge, swim/run→Watch)
uv run iron-dedup --no-fetch                       # offline: cluster by data only (no Strava calls)
uv run iron-dedup --throttle 0.5 --limit 100       # gentler on Strava rate limits

# Explore the UI without Strava (demo data):
DATA_DIR=./data/demo uv run python scripts/seed_demo.py
DATA_DIR=./data/demo uv run uvicorn app.main:app --port 8000
```
