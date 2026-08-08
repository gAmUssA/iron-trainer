# 0058 — Admin console: sync-health telemetry (2026-08-08)

- **Status:** Accepted
- **Beans:** j41l (slice 3 — final — of admin epic 18n4)

## Context

Slices 1–2 (ADRs 0056/0057) gave the admin console per-job inspection and a user
list. Neither answers the fast operational question: *which backend/sync is failing
right now* — a Strava rate-limit burst, an Apple Health ingest regression, a
check-in that started erroring. That needs an aggregate view, not row-by-row.

## Decision

- **`GET /api/admin/health/jobs?days=N`** (`@RequireAdmin`, default 7, clamped
  1–90) returns, over the window:
  - **`kinds[]`** — per job kind: total / succeeded / failed / running / queued /
    other counts and a `failure_rate`, **sorted worst-first** (failure rate, then
    failures, then volume).
  - **`recent_failures[]`** — the 20 newest failed jobs in the window (kind,
    athlete, timestamps, truncated error).
- **The window is a string compare.** `created_at` is stored as UTC ISO-8601
  (`utcNowIso()`), which sorts lexicographically = chronologically, so
  `created_at >= :since` needs no date parsing. Added `PyJson.utcIsoDaysAgo(n)` to
  produce the bound in the identical format.
- **Counts come from one grouped query** (`group by kind, status`) — no row loading
  for the aggregates; only the 20-row failures feed loads entities.
- **Frontend:** a third admin tab (**Health**, now the default landing tab) with a
  1d/7d/30d window switch, a per-kind failure-rate bar (green/amber/red by
  severity), and the recent-failures table.

## Alternatives considered

- **p50/p95 durations per kind** — deferred. Percentiles need loading + sorting the
  windowed rows (the counts don't); the headline question is *what's failing*, not
  *what's slow*. Follow-up bean if latency triage is wanted.
- **Trend sparkline (per-day buckets)** — deferred; needs a second time-bucketed
  query. The window switch (1/7/30d) covers "is it getting worse" for now.
- **A materialized/rolled-up health table** — overkill; the `job` table is small and
  the grouped query is cheap.

## Consequences

- One glance shows the failing kind and a live sample of its errors, scoped to a
  window — the operational entry point the console was built for.
- All-time trends aren't shown; the window caps at 90 days. Fine for triage.
- Completes the admin epic (18n4): jobs (gfb3) + users (y8b2) + health (j41l).

## Verification

Backend `AdminHealthResourceTest` — guard 401; seeded multi-kind jobs (in-window
successes + failures, plus an out-of-window failure) assert per-kind counts,
`failure_rate` = 0.4, window exclusion of the ancient-dated kind from both sections,
and the recent-failures feed. Frontend `tsc` + vite build clean. Local multi-agent
review + Copilot before merge.
