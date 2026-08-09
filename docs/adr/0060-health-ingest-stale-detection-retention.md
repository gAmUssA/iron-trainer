# 0060 — Health-ingest: stale-detection + retention (2026-08-09)

- **Status:** Accepted
- **Beans:** vcf4 (stale detection) + gce2 (retention) — epic 6uys, milestone i5a9

## Context

ADR 0059 shipped the health-ingest audit log + admin Ingests view. Two follow-ups
remained: (1) surfacing when a client has gone **quiet or is failing** (HAE stops
silently under iOS background limits), and (2) keeping the log from growing
unbounded.

## Decision

- **Stale detection (vcf4) — frontend-only, on existing data.** The Ingests
  endpoint already returns `last_by_source` (last ingest per athlete+source). The
  "last per client" table now derives a status per row: **failing** if that last
  ingest errored, **stale Nd** if it's older than `STALE_DAYS` (3), else **ok** — and
  a header badge counts clients needing attention. No new endpoint: the last
  *attempt* + its ok flag surfaces both "went quiet" and "actively erroring".
- **Retention (gce2) — boot-time prune, no new dependency.** A
  `HealthIngestLogRetention` `@Observes StartupEvent` deletes rows older than 90
  days, mirroring `JobRunner.onStart`'s stale-job sweep (best-effort, never blocks
  boot). Railway restarts on every deploy, so it runs regularly enough for a
  slow-growing audit log.

## Alternatives considered

- **A dedicated `/health/stale` endpoint** — unnecessary; `last_by_source` already
  carries what the frontend needs. Compute client-side.
- **`@Scheduled` daily prune** (quarkus-scheduler) — the idiomatic timer, but a new
  extension + native-build surface for a low-priority prune. Deferred to a
  `ponytail:` note: switch to `@Scheduled` if deploys/restarts become rare.
- **Last *successful* ingest for staleness** — chose the last *attempt* instead, so a
  client whose recent syncs are all failing shows as "failing", not silently "ok".

## Consequences

- Admins see at a glance which athletes' health sync stopped or is erroring.
- The audit log is bounded to ~90 days; a long-running instance that never restarts
  wouldn't prune — acceptable, and the note flags the upgrade path.
- `STALE_DAYS` (3) is a client-side constant; easy to tune.

## Verification

`HealthIngestLogRetentionTest` (seed a 200-day-old + a 1-day-old row → prune(90)
removes the old, keeps the recent). Frontend `tsc` + vite build clean. Local
multi-agent review + Copilot before merge.
