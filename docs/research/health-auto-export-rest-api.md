# Health Auto Export → REST API: ingestion research

Date: 2026-07-14 · Bean: iron-trainer-clye · Researched via official help center
(help.healthyapps.dev), the developer's GitHub docs + reference server
(TypeScript models), a production Go ingester (irvinlim/apple-health-ingester,
with real payload fixtures), and a FastAPI integration writeup. Facts labeled
**[doc]** (official), **[community]** (real integrations), **[inferred]**.

## Why this matters for Iron Trainer

Recovery data (sleep, HRV, resting HR) pushed from the athlete's iPhone with
**zero third-party API surface** — no Garmin/Oura/Whoop OAuth, no compliance
widening. The user owns the pipeline; we just receive JSON on our endpoint.
Caveat: REST API automations are **Premium-tier** in the app (subscription or
~$25 lifetime) [doc].

## 1. Payload structure

Top level [doc]:

```json
{ "data": { "metrics": [...], "workouts": [...] } }
```

(`workouts` and newer arrays like `stateOfMind` may be absent; both arrays can
be empty.)

Per-metric [doc]:

```json
{
  "name": "resting_heart_rate",
  "units": "bpm",
  "data": [
    { "qty": 47, "date": "2026-07-13 00:00:00 -0400", "source": "Apple Watch" }
  ]
}
```

- `qty` is the standard value field. Exceptions: `heart_rate` uses
  `Min`/`Avg`/`Max` (capitalized today, lowercase seen in old versions —
  parse defensively) [doc + community]; `blood_pressure` uses
  `systolic`/`diastolic`; aggregated sleep has no `qty` at all.
- **Dates are NOT ISO 8601**: `yyyy-MM-dd HH:mm:ss Z`
  (`"2021-12-24 00:04:00 +0800"`), device-local with numeric offset. Parse
  with `%Y-%m-%d %H:%M:%S %z`; community ingesters also hit 12-hour locale
  variants with a U+202F narrow no-break space before AM/PM — strip it and
  fall back [community].

### Sleep (`sleep_analysis`), Summarize ON → one record per night [doc + fixtures]

```json
{
  "date": "2023-01-31 08:39:12 +0800",
  "asleep": 0,
  "sleepStart": "2023-01-31 00:23:47 +0800",
  "sleepEnd": "2023-01-31 08:39:12 +0800",
  "inBed": 8.145,
  "core": 3.30, "deep": 0.858, "rem": 1.258, "awake": 0.117,
  "totalSleep": 5.417,
  "source": "iPhone|Apple Watch"
}
```

Semantics: with Watch stage tracking `asleep` is often `0` and real sleep is
`core+deep+rem` (or `totalSleep` when present, newer versions). Resolution
order: **`totalSleep` → `core+deep+rem` → `asleep` → `inBed`**. Stage keys:
`core`, `deep`, `rem`, `awake`, plus `inBed`/`inBedStart`/`inBedEnd`.
Summarize OFF gives per-segment records
(`startDate`/`endDate`/`qty`/`value: Awake|Asleep|In Bed|Core|REM|Deep`).

## 2. Metric identifiers (from the developer's own server code) [doc]

| Metric | Identifier | Units |
|---|---|---|
| Sleep | `sleep_analysis` | `hr` |
| HRV (SDNN) | `heart_rate_variability` | `ms` |
| Resting HR | `resting_heart_rate` | `bpm` |
| Wrist temp (sleep) | `apple_sleeping_wrist_temperature` | `degC`/`degF` |
| Respiratory rate | `respiratory_rate` | count/min |
| Weight | `weight_body_mass` | `kg`/`lb` (user pref) |
| VO2max | `vo2max` | mL/min·kg |
| Heart rate | `heart_rate` | `bpm` (Min/Avg/Max shape) |

Units follow user preferences and are declared in the payload — always read
`units`, never assume [doc].

## 3. Automation config (user side) [doc]

- Automations → New → **REST API**: URL, data types (individually selectable),
  format JSON, aggregation (choose **days** + **Summarize ON**), cadence.
- **Custom headers supported** — the docs literally show
  `Authorization: Bearer your-token` and `X-API-Key`. This is our auth.
- App auto-adds: `automation-name`, `automation-id`, `automation-aggregation`,
  `automation-period`, `session-id` headers.
- **Batch Requests ON** recommended (splits big exports; avoids iOS memory
  kills) [doc + community].
- Overlapping export windows are re-sent — dedup is the receiver's job
  [community, consistently observed].

## 4. Delivery reliability [doc FAQ]

iOS won't run automations while locked, at exact times, or in Low Power Mode;
"cannot sync at a guaranteed time or fixed interval." Expect late/missing/
duplicate pushes. Treat the pipeline as eventually consistent — consumers read
"latest available" recovery data, never block on today's push. Manual fallback:
running the automation from the app/widget.

## 5. Gotchas checklist

1. Per-metric value shape (`qty` vs `Min/Avg/Max` vs sleep's stage fields).
2. Non-ISO dates, possible 12-hour + U+202F variants; derive `local_date`
   from the embedded offset, not server time.
3. Units vary by user prefs (lb vs kg, degF vs degC) — normalize on ingest.
4. Empty `data` arrays for selected-but-sampleless metrics.
5. Last night's sleep may arrive incomplete early and fuller later — upsert.
6. Don't cap request body at 1 MB (historical backfills are huge); return 200
   fast — the app surfaces non-2xx as user-visible errors.

## 6. Recommended Iron Trainer design

- `POST /api/health/ingest`, bearer-authenticated with the existing device
  token (resolves athlete; payload has no user identity — only device names).
- Lenient Pydantic (`extra="allow"`, everything optional); 200 quickly;
  process idempotently.
- New `daily_recovery` table, wide row upserted on `(athlete_id, local_date)`,
  last-write-wins: `sleep_h`, `deep_h`, `rem_h`, `awake_h`, `sleep_start`,
  `sleep_end`, `hrv_ms`, `rhr_bpm`, optional `weight_kg`, `vo2max`,
  `respiratory_rate`, `wrist_temp_c`. Sleep rows keyed by `sleepEnd`'s local
  date (wake-up day). Quality proxy: `(deep+rem)/sleep_h`.
- Readiness integration: `readiness.compute()` gains optional recovery
  modifiers (low HRV vs athlete's own baseline, elevated RHR, short sleep) —
  same signal-not-noise rules; check-in feel-vs-data line gets real sleep
  numbers to point at.
- User setup doc: JSON + aggregate-by-days + Summarize ON + Batch ON, daily
  cadence, Authorization header with a token minted in Settings.
