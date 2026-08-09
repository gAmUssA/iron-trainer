# 0059 — Admin: health-ingest audit log + view (2026-08-08)

- **Status:** Accepted
- **Beans:** j05e (foundation) + bvif (admin view) — epic 6uys, milestone i5a9

## Context

`POST /api/health/ingest` (Apple Health data from Health Auto Export *and* the
native iOS app) is fire-and-forget: it upserts `daily_recovery` and returns counts,
but persists **no record of the ingest event**, and it is not a `Job`. So the admin
console (epic 18n4) couldn't answer "did health data arrive, when, from which
client, and what was dropped?" — HAE in particular can silently stop under iOS
background limits, invisibly. Both clients also hit the *same* endpoint with no
source marker.

## Decision

- **Audit every ingest.** New `health_ingest_log` table (V7 migration) + a row
  written on **every** `POST /api/health/ingest` — success *and* the malformed-JSON
  path — with `athlete_id` (nullable), `source`, `received_at`, `ok`, `days_stored`,
  `records`, `unknown_metrics`, `bad_dates`, `byte_size`, `user_agent`, `error`.
  The write is **best-effort in its own transaction** — a logging failure never
  fails the ingest. **The payload is not stored** (PII + size); only the stats.
- **Source detection.** `X-Ingest-Client` header decides: the native app now sends
  `native`; a HAE automation can be configured with `hae` (its User-Agent is a
  best-effort fallback). Unknown otherwise.
- **Admin surface.** `GET /api/admin/health/ingests?days&athlete_id&source&ok&limit&offset`
  (`@RequireAdmin`): a windowed, filterable, paginated feed **plus** `last_by_source`
  — the last ingest per (athlete, source) *regardless of window* (a `max(id)`
  group-by), so a client that went quiet still shows its last event. A new **Ingests**
  admin tab renders both tables.

## Alternatives considered

- **Model each ingest as a `Job`** — rejected: jobs are athlete-scoped async work with
  queued/running/terminal states; an ingest is a synchronous external POST. A
  purpose-built log is simpler and cheaper.
- **Store the raw payload** — rejected: PII + unbounded size; the stats answer the
  operational question.
- **Section inside the Health tab (per the bean text)** — chose a dedicated tab
  instead; the Health tab is job sync-health, and a shared window control would be
  ambiguous.

## Consequences

- Health-data ingestion is now observable per client/athlete; `last_by_source` is the
  foundation for stale-sync detection (bean vcf4).
- The native `X-Ingest-Client: native` header only takes effect on the next
  TestFlight build; untagged posts fall back to User-Agent → `unknown`, handled
  gracefully.
- The log grows ~a few rows/day/athlete; unbounded for now — retention is bean gce2.

## Verification

`HealthSourceDetectTest` (pure unit, runs without Docker) covers source mapping;
`HealthIngestWriteTest` asserts the malformed path still writes a source-tagged
`ok=false` row; `AdminIngestsResourceTest` covers the guard (401), source/ok filters,
and window inclusion/exclusion. Frontend `tsc` + vite build clean. Local multi-agent
review + Copilot before merge.
