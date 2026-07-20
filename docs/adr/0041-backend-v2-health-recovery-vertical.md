# 0041 — Backend v2: health vertical — recovery read + Health-Auto-Export ingest (2026-07-20)

Date: 2026-07-20
Epic: iron-trainer-eom4 (Phase 7) · Feature: iron-trainer (health vertical) · Pattern: ADR 0020

## Context

Two of the remaining Phase-7 endpoints: the recovery data path that feeds
readiness. The phone (Health Auto Export) POSTs HRV/sleep/RHR/etc. to
`/api/health/ingest`; `/api/health/recovery` reads them back. backend-v2 already
*read* `daily_recovery` (readiness) but only mapped 4 of its columns and had no
ingest.

## What was built

- **`DailyRecovery` entity expanded** to all 14 `daily_recovery` columns
  (updated_at, deep_h, rem_h, awake_h, sleep_start/end, weight_kg, vo2max,
  respiratory_rate, wrist_temp_c) so recovery rows round-trip in full and the
  read's `model_dump` field order is reproduced.
- **`HealthIngest.parsePayload`** — port of `app/health_ingest.py`. Reduces a
  Health-Auto-Export payload to `{local_day: partial row}` + parse stats:
  - offset-aware dates (`yyyy-MM-dd HH:mm:ss Z`, 12-hour variant with the U+202F
    narrow space, ISO fallback) keyed to the **calendar day in the timestamp's own
    offset**;
  - unit conversions (lb→kg, °F→°C, banker's-rounded to 2dp);
  - sleep-hours resolution (`totalSleep` → `core+deep+rem` → `asleep` → `inBed`);
  - same-day multi-sample **averaging** per metric; unknown metrics / bad dates
    tracked in stats, never fatal.
- **`GET /api/health/recovery?days=N`** — newest-first rows (clamped 1..365),
  `model_dump(exclude id/athlete_id)` order.
- **`POST /api/health/ingest`** — parse → upsert per day (last-write-wins per
  field, own transaction so one bad day doesn't roll back the batch) → `{ok, days,
  parsed{records, unknown_metrics, bad_dates}}`. Malformed JSON → a fast `200
  {ok:false, error, days:0}` (the app surfaces non-2xx as automation errors).
  **Auth is enforced only when there's data to store** (upsert → `current.require`
  → 401), matching FastAPI's `upsert_daily_recovery → current_athlete_id` — so a
  malformed or empty payload from an unauthenticated caller still gets a 200, not
  a 401. The 503-not-500 deep-health lesson (ADR 0040) is why the shallow paths
  aren't transactional.

## Testing

- **`HealthIngestTest`** (pure): offset-local-day keying, lb→kg / °F→°C, same-day
  averaging, sleep-stage resolution, `sleep_start/end` isoformat (colon offset),
  unknown-metric + bad-date stats.
- **`HealthEndpointTest`** (`@QuarkusTest`): ingest → 200 + summary, read-back;
  malformed JSON → `{ok:false}`. Dedicated default athlete 6001 for the FK.
- **Parity** (`test_health_ingest_recovery_parity`): the SAME payload POSTed to
  both backends (idempotent last-write-wins upsert) yields byte-identical ingest
  responses AND byte-identical `/api/health/recovery` — verified vs real backends.
- Full v2 suite: 178 green.

## Remaining for Phase 7

PUT /api/athlete/profile · export ZIP bundles · device pairing (token minting).

## Code-review fixes (applied before merge)

5 CONFIRMED parity findings fixed:
1. **parseDate leniency** — matches Python `strptime(%z)` + `fromisoformat`: colon
   OR non-colon offsets, plus offset-less (naive) and bare-date ISO fallbacks
   (space or `T`). Was dropping those as bad_dates.
2. **date/startDate (+ sleepEnd/date) fallback** — Python truthy `or`: an
   empty-string date now falls through to `startDate` instead of being kept as `""`.
3. **num() booleans** — Python `bool` is an `int` subclass, so a JSON boolean qty
   coerces to 1.0/0.0 instead of being dropped.
4. **`?days` 422 parity** — `Params.intParam` (not a raw `@QueryParam int`) so a
   non-numeric `?days` is 422, matching FastAPI (a raw int would 404 on RESTEasy).
5. **ingest auth** — `current.require()` moved INSIDE the per-day try, so an
   unauthenticated caller with data gets `200 {ok:true, days:0}` (the 401 is
   swallowed), exactly matching FastAPI's `except Exception` around
   `upsert_daily_recovery → current_athlete_id`.

v2 suite 179 green; health parity re-verified vs real backends.
