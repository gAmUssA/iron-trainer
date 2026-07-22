# 🏊‍♂️🚴‍♂️🏃‍♂️ Iron Trainer

AI-adaptive triathlon training for **IRONMAN 70.3 New York — Sat Sep 26, 2026**.

Pulls your Strava history, computes training load (TSS / CTL / ATL / TSB), generates
an LLM-adaptive 70.3 plan (bounded by a safety validator), exports structured `.fit` /
`.zwo` / `.itw` workouts you import into **TrainingPeaks** / **Garmin** / **Apple
Workouts**, ingests recovery (sleep / HRV / resting-HR) natively from **Apple Health**,
and tracks progress in a web dashboard.

## Stack

- **Backend:** Java / Quarkus (GraalVM native) + Hibernate ORM with Panache +
  Flyway (`backend-v2/`), on **Postgres/Supabase**. Also serves the built web SPA
  (single front door). *(The original Python/FastAPI backend was decommissioned
  2026-07-21 — bean foi1.)*
- **Frontend:** React (Vite) + Recharts (`frontend/`), tabbed UI (Today / Training
  Plan / Fitness / Recovery / Nutrition / Tests / Settings), responsive/mobile-friendly
  with light & dark themes (header toggle, persisted) — Space Grotesk + IBM Plex Mono
- **AI:** Claude API for plan generation (via LangChain4j)
- **Integrations:** Strava API (read), TrainingPeaks / Garmin (file export),
  Apple Workouts via the `.itw` file + native Apple Health ingestion in the
  iOS 18 helper app (`ios/`)

## Quick start (local dev)

1. **Configure secrets**

   ```bash
   cp .env.example .env
   # Fill in STRAVA_CLIENT_ID / STRAVA_CLIENT_SECRET (https://www.strava.com/settings/api)
   # and ANTHROPIC_API_KEY (https://console.anthropic.com/)
   ```

   When creating the Strava app, set **Authorization Callback Domain** to `localhost`.

2. **Backend** (Quarkus — see [`backend-v2/README.md`](backend-v2/README.md) for details)

   ```bash
   cd backend-v2
   ./mvnw quarkus:dev          # live-reload dev mode on :8080 (Dev UI at /q/dev/)
   ```

3. **Frontend** (separate terminal)

   ```bash
   cd frontend
   npm install
   npm run dev          # http://localhost:5173 (proxies /api to Quarkus on :8080)
   ```

Quarkus dev mode uses **Dev Services** to start a throwaway Postgres automatically —
no local DB setup needed.

## Production image

The production container is `backend-v2/Dockerfile` (built from the repo root: it
compiles the SPA, injects it into the Quarkus resources, and builds a GraalVM native
image that serves both the API and the web app on `:8080`). Deploy details in
[`docs/deploy.md`](docs/deploy.md).

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
4. **Export** → each session downloads as a structured `.fit` (all sports),
   `.zwo` (bike), and `.itw` (Apple Workouts), or grab a weekly / full-plan `.zip`.
   **Garmin Connect** imports the `.fit` for any sport (Workouts → Import → Send to
   Device) — this is the path for run & swim. **TrainingPeaks** imports the bike
   `.zwo` (Workout Library → Workout Import); its import only accepts power-based
   bike `.zwo`, not `.fit` and not run/swim — so use Garmin Connect for those.
   **Apple Workouts** uses the `.itw` file: open it in the iOS 18 helper app
   (`ios/`), which builds the native workout on-device and schedules it to your
   Apple Watch. (`.itw` is Iron Trainer's own JSON — Apple's binary `.workout`
   format can't be generated on a server.)
5. **Track** progress on the dashboards: Performance Management Chart, weekly
   volume, per-sport trends, and a projected 70.3 finish time with **cut-off
   checks** (swim 1:10, bike 5:30 cumulative, finish 8:30 — the standard 70.3
   limits; verify against the official athlete guide and override in `.env`
   via `CUTOFF_SWIM_S` / `CUTOFF_BIKE_S` / `CUTOFF_FINISH_S`).

## Cheap cloud deploy (optional)

The same image deploys to Fly.io / Railway with a persistent volume at `/data`.
No code changes vs local. See [`docs/deploy.md`](docs/deploy.md).

## Architecture decisions

The **why** behind major decisions is recorded as ADRs in
[`docs/adr/`](docs/adr/README.md) (DB abstraction, multi-user auth, Apple Workouts
export, device-pairing live sync).

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
| GET      | `/api/export/workout/{id}.fit` · `.zwo` · `.itw`             | single workout file                         |
| GET      | `/api/export/week/{week_start}.zip` · `/api/export/plan.zip` | bundles                                     |

## Tasks (Quarkus / Maven)

Run from the `backend-v2/` directory. Flyway migrations apply automatically at
startup in dev/test. See [`backend-v2/README.md`](backend-v2/README.md) for more.

```bash
./mvnw quarkus:dev                                 # live-reload dev API on :8080 (Dev UI at /q/dev/)
./mvnw test                                        # JVM test suite (Dev Services spins up Postgres)
./mvnw package                                     # build the runnable JAR
./mvnw package -Dnative -Dquarkus.native.container-build=true   # GraalVM native image
```
