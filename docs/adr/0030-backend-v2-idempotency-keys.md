# 0030 — Backend v2: idempotency keys for proxied writes (2026-07-18)

Date: 2026-07-18
Status: Accepted
Epic: iron-trainer-eom4 (Phase 7) · Feature: iron-trainer-0o9b · Pattern: ADR 0020 / 0023

## Context

The strangler forwards writes to backend-v2 and, on an *undelivered* transport
error (connect/pool timeout), safely replays them locally. But a write that IS
delivered whose response is then lost (read timeout / protocol error) returns a
502 with NO local retry (ADR 0023) — server-side double-apply is prevented, but a
**client** that auto-retries on 5xx can still re-send the mutation. Closing that
window needs idempotency keys honored end-to-end. This is the last write-safety
prerequisite before flipping any non-idempotent write endpoint to backend-v2.

FastAPI has no idempotency handling, so this is net-new backend-v2 infrastructure
(no byte-parity reference). The strangler already forwards the `Idempotency-Key`
header (it is not in the drop set).

## What was built

- **`IdempotencyStore`** — a find/save seam over Quarkus Cache. Stores a completed
  write's response (`status`, body object, media type, replayable headers).
- **`IdempotencyFilter`** (`@Provider`, `@Priority(5100)`) — request + response
  filter. On a write with an `Idempotency-Key` and a resolved athlete: a cache
  hit `abortWith`s the stored response (`Idempotency-Replayed: true`) so the
  mutation never runs; a miss lets the write proceed and the response filter
  caches a **2xx** outcome. Runs after the auth filter (default USER=5000) so the
  athlete is resolved.
- **Cache key = `(athlete, method, path, Idempotency-Key)`.** Including method +
  path means the same client key reused on a different endpoint is a miss (runs
  normally) rather than replaying the wrong endpoint's response.
- **Async writes are skipped** (`?async=1`). Those return a job-submit envelope
  and are already deduped per (athlete, kind) by the job system (ADR 0029);
  caching the `queued` envelope would defeat that and pin a retry to a stale job
  instead of resubmitting/healing it. Idempotency here is for **sync** writes.
- **Headers replayed.** The stored entry carries the response headers, so a replay
  re-emits Location / ETag / custom headers; **Set-Cookie is never replayed** (a
  cached auth cookie must not be re-emitted) and content-length/-type are
  recomputed.

## Decisions

- **In-memory now, persistence-ready.** The store is Quarkus Cache (Caffeine,
  bounded 10k / TTL 24h — config only). backend-v2 is a single instance, so a
  process-wide cache is coherent, and client 5xx-retries are near-immediate, so a
  24h window covers the real risk. Deliberately NOT a DB table: Flyway is off in
  prod (schema is the shared pg_dump baseline), so a new table would mean a risky
  prod schema change (the deploy-crash-sensitive path). If durable exactly-once
  is ever needed, swap in `quarkus-redis-cache` — extension + config only, no
  change to `IdempotencyStore` or the filter.
- **Only 2xx is cached.** A 5xx must stay retryable (the first attempt may have
  failed before committing); a 4xx is deterministic and will recur anyway.
- **Tenant-scoped keys.** The key is `(athlete, Idempotency-Key)` so two athletes'
  keys can't collide. An unauthenticated write (no resolved athlete) is not
  deduped — none of the flip-target endpoints are unauthenticated.

## Ceilings

- **In-memory:** entries are per instance and lost on restart — a retry spanning
  a restart could re-apply. Acceptable (retries are near-immediate); documented.
- **No in-flight lock:** two *exactly-simultaneous* duplicates both miss and both
  run. The real case — a sequential retry after the first completes — is covered.
  Job-based writes (sync/dedup/nutrition_regen) are additionally deduped per
  (athlete, kind) by the job system (ADR 0029).

## Remaining half (follow-ups)

Server-side honoring is complete; making it *effective* needs a client that sends
a stable `Idempotency-Key` across retries of the same logical operation. The web
frontend does not currently auto-retry POSTs (and job endpoints are deduped), so
this is latent until a retrying client (or the strangler generating a per-write
key) adopts it — tracked separately. Does not block the write flip: absent a key,
behavior is unchanged.
