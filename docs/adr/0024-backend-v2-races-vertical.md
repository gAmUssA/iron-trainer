# 0024 — Backend v2: races vertical (2026-07-17)

Date: 2026-07-17
Status: Accepted
Epic: iron-trainer-eom4 (Phase 7) · Feature: iron-trainer-38ws · Pattern: ADR 0020

## Context

The `races` router (race catalog + per-athlete race selection) was one of the
last unported read/write surfaces on the FastAPI side. Routine strangler
vertical port — no new architecture, so this ADR is a short record of the
port for completeness (per the feature workflow).

## What was ported

- **`GET /api/races`** — the IRONMAN catalog, filterable by
  `distance`/`country`/`month` (YYYY-MM string range)/`q` (name/city
  case-insensitive LIKE), ordered by date. `Race` Panache entity over the
  existing `race` baseline table; `toDict()` reproduces `Race.model_dump()`'s
  field order.
- **`PUT /api/athlete/race`** — select a catalog race by id (copies
  name/date/distance + cutoffs onto the athlete) or a custom race (name + date
  required; cutoffs derived from distance via `cutoffsFor`, default "70.3").
  Returns `effective_race()` — the athlete's selection or the config default.
- Extended `Athlete` with the seven race columns (already in the baseline).
- Config defaults (`irontrainer.race-name` = "IRONMAN 70.3 New York",
  `race-date` = 2026-09-26, cutoffs 4200/19800/30600) mirror
  `backend/app/config.py`.

## Parity notes

- The catalog is seeded on FastAPI startup (`db._seed_races`) into the shared
  Postgres, so both backends read identical rows — no extra seeding.
- `effective_race()` `or`-fallbacks mirror Python truthiness (null/empty/0 →
  default), consistent with the other verticals' banker's-truthy handling.
- Validation errors are 400 on both (race-not-found; custom missing name/date),
  matching FastAPI's `HTTPException(400)` from the caught `ValueError`.

## Verification

8 new parity tests (catalog, distance/country/q/month filters, set-by-id,
set-custom with cutoff derivation, not-found-400, custom-missing-400). Full
parity gate 42/42, backend-v2 unit suite 85/85.

## Not done here

- Analytics-reads remainder (`/metrics/weekly`, `/metrics/trends`,
  `/activities`) — a separate metrics follow-up (pmc + readiness/today already
  ported).
- `race_readiness` (`/metrics/readiness`) — bean na5p.
