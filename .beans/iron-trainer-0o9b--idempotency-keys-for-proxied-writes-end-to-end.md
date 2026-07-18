---
# iron-trainer-0o9b
title: idempotency keys for proxied writes (end-to-end)
status: completed
type: task
priority: normal
created_at: 2026-07-17T05:27:36Z
updated_at: 2026-07-18T20:49:46Z
parent: iron-trainer-eom4
---

From PR #56 review: when a proxied write is DELIVERED but its response is lost (read timeout / protocol error), the strangler returns 502 and does not retry — but a client that auto-retries on 5xx can still double-apply a non-idempotent mutation. Closing this fully needs idempotency keys end-to-end: client sends Idempotency-Key (already forwarded by the strangler now), backend-v2 dedupes on it. Deferred; the 502-no-local-retry already prevents server-side double-apply.

## Summary of Changes

Server-side idempotency for proxied writes in backend-v2 (ADR 0030).

- **IdempotencyStore**: find/save seam over Quarkus Cache (Caffeine, in-memory, bounded 10k / TTL 24h). Persistence-ready — swap in quarkus-redis-cache (extension + config only) with no code change.
- **IdempotencyFilter** (@Provider @Priority(5100), runs after auth): a write with an Idempotency-Key + resolved athlete replays the cached 2xx response (Idempotency-Replayed: true) instead of re-applying; misses run and cache on the way out. Only 2xx cached (5xx stays retryable); key = (athlete, Idempotency-Key).
- Config: quarkus.cache.caffeine."idempotency".{expire-after-write=24H, maximum-size=10000}.
- Tests: same key → replayed + no double-create; different/no key → runs normally; async regenerate envelope replay. Full suite 123 green.

Closes the server-side half of the 502-lost-response double-apply window (the strangler already forwards the header). Ceilings: in-memory (lost on restart; near-immediate retries covered), no in-flight lock (exactly-simultaneous duplicates both run; job endpoints separately deduped).

## Remaining (follow-up)
Making it EFFECTIVE needs a retrying client to send a stable Idempotency-Key. The web frontend doesn't auto-retry POSTs and job endpoints are deduped, so this is latent until a retrying client (or strangler-generated keys) adopts it. Does NOT block the write flip (absent a key, behavior unchanged).

## Code-review fixes (PR #70)

High-effort review found 4 findings (3 PLAUSIBLE correctness, 1 CONFIRMED cleanup); all fixed:
- #1: cache key now scoped by (athlete, method, path, key) — a reused key on a different endpoint is a miss (runs), not a wrong replay.
- #2: async writes (?async=1) are SKIPPED — job dedup (ADR 0029) owns retry-safety for those; caching the 'queued' envelope would pin a retry to a stale job.
- #3: response headers captured + replayed (Location/ETag/custom); Set-Cookie never replayed; content-length/-type recomputed.
- #4: corrected the misleading in-flight comment in IdempotencyStore.
Added tests: cross-endpoint-key no-cross-replay, async-not-cached. Full suite 125 green.
