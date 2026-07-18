# 0026 — Backend v2: trends vertical (insights engine) (2026-07-17)

Date: 2026-07-17
Status: Accepted
Epic: iron-trainer-eom4 (Phase 7) · Feature: iron-trainer-ory3 · Pattern: ADR 0020

## Context

`GET /api/metrics/trends` was the last unported `analytics_router` read — and
the biggest, because the endpoint returns one bundle that requires the entire
`insights.build` engine (~250 lines) plus `sport_trends`. Unlike the other
verticals it is not sub-sliceable: a partial response has no parity.

## What was ported

`Dashboards.sportTrends` + a new `Insights` class mirroring `insights.py`:

- **`sport_trends`** — per-sport progression points: Bike (weighted power +
  efficiency factor power/HR), Run (pace sec/km + EF speed·100/HR), Swim (pace
  sec/100m). `round(x, 0)` → float; EF `round(x, 2)`.
- **`rolling_mean`** — trailing mean over the previous 28 days, single-pass
  sliding window (points are date-ordered), `round(mean, 1)`.
- **`slope_pct`** — least-squares % change over the last 84 days
  (`slope × span / mean × 100`), null with < 4 points or a degenerate fit.
- **`sport_insights`** — per-sport metric/verdict/rolling; Bike & Run prefer EF,
  fall back to power/pace when < 4 EF points; `_verdict` steady band ±1.5%.
- **`intensity_mix`** — 12-week hours per IF bucket (easy/endurance/tempo/hard/
  unknown; IF 0.0 is falsy → unknown), `round(v, 2)`.
- **`personal_records`** — best 40-min power, longest ride, fastest 5k, longest
  run, fastest 1k swim; each compares raw value against the stored *rounded*
  best; `round()` → int.
- **`ctl_trajectory`** — current CTL, 4-week ramp, straight-line race-day
  projection + weekly projection points.
- **`freshness`** — last activity date + days stale.

The endpoint windows only the returned chart points by `days`
(`Query(ge=0, le=3660)`, `days<=0` = unbounded); insights always derive from the
full record. "Today" is host-local `LocalDate.now()`, matching Python
`date.today()`.

## Parity notes

- `insights._day` is `date.fromisoformat(str(v)[:10])` (first-10-chars parse),
  distinct from `dashboards._day` (Iso, Z-aware) — ported faithfully as
  `Insights.day`.
- All `round()` calls go through `Py.round`/`Py.roundInt` (banker's, exact
  binary value) — the whole point of the parity suite.
- Python truthiness on `weighted_power or avg_power` and the IF 0.0 → unknown
  gate use `Py.truthy` (null/0 falsy).

## Verification

3 parity tests: full trends bundle byte-identical (a `seeded_trends` fixture
gives 4+ points per sport across the 84-day window so rolling/slope/PRs/
intensity all fire), day windows (0/30/365), and 422 on out-of-range/non-int
`days`. **Full parity 54/54, backend-v2 unit suite 85/85.**

## Consequence

All `analytics_router` reads are now ported (pmc, readiness/today, weekly,
activities, trends). Remaining analytics: `race_readiness` (na5p).
