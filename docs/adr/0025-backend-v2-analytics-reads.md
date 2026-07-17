# 0025 — Backend v2: analytics reads (weekly volume + activities feed) (2026-07-17)

Date: 2026-07-17
Status: Accepted
Epic: iron-trainer-eom4 (Phase 7) · Feature: iron-trainer-ufgr · Pattern: ADR 0020

## Context

The `analytics_router` reads were partly ported (pmc, readiness/today). This
ports two more — `/metrics/weekly` and `/activities` — leaving only
`/metrics/trends` (a ~500-line `insights.build` + `sport_trends` analytics
engine, split to its own bean) and `race_readiness` (na5p).

## What was ported

- **`GET /api/metrics/weekly`** (`weeks`=16) — `weekly_volume`: bucket the
  athlete's non-duplicate activities by ISO week (Monday), sum hours
  (`moving_time/3600`), `distance_km` (`distance/1000`), and tss per sport, each
  rounded to 1dp (`Py.round` banker's). **Faithful quirk:** `total_hours`/
  `total_tss` sum the *already-rounded* per-sport values, then round again.
  Last `weeks` buckets, ascending by week start.
- **`GET /api/activities`** (`limit`=500, `include_duplicates`=true) — the feed,
  most-recent first (`reversed[:limit]`); `count` is the TOTAL unfiltered
  activity count; `duplicates` counts dupes within the returned page.
  `Activity.toDict()` reproduces the 23-field `model_dump`; added the `raw_json`
  column mapping (read-only — written by the sync).

## Parity notes

- Key order is irrelevant to the parity `==` (Python dict equality), so
  `toDict`/`by_sport` maps need only matching keys + values, not order. The
  `weeks` list order (by week start) does matter — both sort identically.
- Both backends read the same shared Postgres, so a `seeded_history` fixture
  (5 activities across 2 ISO weeks) makes the tests exercise real data while
  `v1==v2` stays robust to whatever else the DB holds.

## Verification

4 parity tests (weekly, weekly window 1/4/52, activities, activities filters
include_duplicates/limit). Full parity 47/47, backend-v2 unit suite 85/85.

## Not done here

- `GET /metrics/trends` — `sport_trends` + `insights.build` (~500 lines:
  trendlines, improving/declining verdicts, intensity mix, PRs, CTL trajectory,
  freshness). Its own bean.
- `race_readiness` (`/metrics/readiness`) — bean na5p.
