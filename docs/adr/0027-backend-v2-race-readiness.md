# 0027 — Backend v2: race-readiness projection (2026-07-17)

Date: 2026-07-17
Status: Accepted
Epic: iron-trainer-eom4 (Phase 7) · Feature: iron-trainer-na5p · Pattern: ADR 0020

## Context

`GET /api/metrics/readiness` (race readiness — projected splits vs cut-offs) is
the last analytics read, distinct from `/api/metrics/readiness/today` (the daily
go-hard/easy call, already ported). The `na5p` bean also covered nutrition
race-day, which reuses this projection — split to its own bean now that
race_readiness exists on backend-v2.

## What was ported

`RaceReadiness` (dashboards package) mirroring `dashboards.race_readiness`:

- **Legs** — swim `= CSS·1.06 × distance/100`, bike `= distance / recent long-ride
  speed`, run `= threshold_pace·1.10 × distance/1000`. Leg distances from
  `LEG_DISTANCES` (70.3 / 140.6). A missing threshold/speed adds to `missing`.
- **`recentBikeSpeed`** — mean `avg_speed` of Bike rides ≥ 1h in the last 84 days.
- **Transitions** T1 5min + T2 3min; **total** = sum of rounded leg seconds +
  transitions (if any legs).
- **`cutoffChecks`** — cumulative projected time vs the cut-offs (swim / swim+T1+
  bike / +T2+run), with `margin` and `ok`.

The endpoint assembles: current CTL (last metric row), effective-race cut-offs +
distance, non-duplicate activities, thresholds.

## Parity notes

- **Faithful quirk:** a leg's `seconds` uses `round()` but its `display` uses
  `int()` on the raw float — they can differ by 1s. `Py.roundInt` + `fmtHms`
  (truncating) reproduce both.
- **`str(distance)` lookup:** Python does `LEG_DISTANCES.get(str(distance), ...)`,
  so a null distance (no race selected) stringifies to a miss → 70.3 default.
  `Map.of` is null-hostile, so the port uses `String.valueOf(distance)` — without
  it, a raceless athlete 500s (caught in review-of-self via the parity gate).
- All `round()` via `Py.round`/`roundInt` (banker's); `Py.truthy` for the
  threshold/speed presence gates; `Iso.parseDate` for `_day`.

## Verification

1 parity test (full projection: legs, transitions, total, CTL, cut-off checks
byte-identical, with thresholds + recent rides seeded) + a backend-v2 smoke test.
Full parity 55/55, backend-v2 unit suite 86/86.

## Consequence

**All `analytics_router` reads are now ported.** Remaining from `na5p`: nutrition
race-day (`GET /api/nutrition/race-day` = `compute_race_day_plan` +
`validate_fueling`, reusing this projection) — split to its own bean.
