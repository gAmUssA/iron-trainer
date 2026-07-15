# 0019 — Recovery-data ingestion via Health Auto Export

Date: 2026-07-15
Status: Accepted
Bean: iron-trainer-clye · Research: docs/research/health-auto-export-rest-api.md

## Decision

1. `POST /api/health/ingest` receives Health Auto Export's
   `{data:{metrics:[...]}}` pushes, authenticated by the existing bearer
   device-token (custom `Authorization` headers are officially supported by
   the app). Lenient by contract: always answers 200-shaped JSON fast,
   ignores unknown metrics, tolerates empty data arrays and malformed bodies
   — the app surfaces non-2xx as user-visible automation errors.
2. `health_ingest.parse_payload()` encodes the researched gotchas: non-ISO
   dates (`%Y-%m-%d %H:%M:%S %z` + 12-hour/U+202F fallback), local-date
   derived from the embedded offset, sleep resolution
   `totalSleep → core+deep+rem → asleep → inBed` keyed by wake-up day,
   lb→kg and °F→°C normalization, same-day samples averaged.
3. `daily_recovery` table (Alembic `a1b3c5d7e9f1`), wide row upserted
   per-field last-write-wins on (athlete, date) — overlapping windows are
   re-sent by design and last night's sleep arrives incomplete early.
4. Readiness gains recovery modifiers vs the athlete's OWN baselines
   (≥5 prior samples): sleep < 6 h, HRV < 80 % of baseline, RHR > baseline
   +5 bpm. They can only downgrade green → easy, never upgrade, and stale
   data (> 2 days old) says nothing — a phone that stopped pushing is not a
   bad night's sleep. Flags flow into the check-in story and LLM context
   automatically via the existing readiness plumbing.
5. Eventually-consistent by design: iOS gives no delivery-time guarantees,
   so consumers read latest-available; nothing blocks on today's push.

## Deferred

- ~~Settings UI for minting a dedicated ingest token~~ — shipped as follow-up:
  POST /api/device/ingest-token (session-auth, plaintext shown once, hash
  stored) + HealthIngestCard on the web Settings tab with copy-paste setup.
- Workouts array ingestion — noted on k5d0 as a potential full Strava
  escape hatch (Garmin → Apple Health → us); this endpoint is the
  foundation it would extend.
