# 0064 — Admin: p50/p95 job durations per kind (2026-08-09)

- **Status:** Accepted
- **Bean:** og06 (admin epic 18n4; deferred from j41l / ADR 0058)

## Context

The admin sync-health view (ADR 0058) shows per-kind failure rates but not *how
long* jobs take — latency triage was deferred because percentiles need the actual
rows, not the cheap count group-by.

## Decision

- **`GET /api/admin/health/jobs`** now includes, per kind, `p50_ms` / `p95_ms` (and
  `timed`, the sample count) of `finished_at − started_at`.
- **Computed in Java:** load the window's jobs that actually ran (both `started_at`
  and `finished_at` set), bucket durations by kind, sort, nearest-rank percentile.
  `durationMs` skips unparseable / negative spans (clock skew). The job table is
  small, so loading is fine — `ponytail:` note points to Postgres
  `percentile_cont(order by finished::ts − started::ts)` if it ever grows.
- **Frontend:** two columns (`p50` / `p95`, human-formatted ms→s→m) on the Health
  tab's per-kind table, tooltip'd with the timed-run count.

## Alternatives considered

- **SQL `percentile_cont`** — the scalable answer, but needs casting the string
  timestamp columns and a Postgres-specific native query; not worth it at current
  volume. Documented as the upgrade path.
- **Include failed jobs' durations** — kept: any job with both timestamps ran, so a
  slow failure counts toward latency. Jobs that never started contribute nothing.

## Consequences

- Latency is visible per kind for triage; no new query cost worth worrying about at
  this scale.
- `p50_ms`/`p95_ms` are null when a kind has no timed runs in the window.

## Verification

`AdminHealthDurationTest` (pure unit — durationMs edge cases + nearest-rank
percentile) runs without Docker; `AdminHealthResourceTest.durationPercentilesPerKind`
seeds 1/2/3/4 s runs and asserts p50=2000ms, p95=4000ms via the endpoint. Frontend
builds. Local review + Copilot before merge.
