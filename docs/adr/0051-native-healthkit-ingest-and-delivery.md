# 0051 — Native HealthKit ingest client + delivery (2026-07-21)

Date: 2026-07-21
Beans: iron-trainer-n1zt (this task) · epic iron-trainer-2f2c
Related: ADR 0048 (auth), 0049 (reader), 0050 (assembler) — this task ties them to
the `/api/health/ingest` endpoint. docs/research/native-healthkit-ingestion.md.

## Context

The reader (aw8t) and assembler (90iu) exist; this is the piece that makes native
data actually reach the backend and keeps it flowing — the point at which Health
Auto Export becomes redundant. Zero backend changes: it emits the same HAE-shaped
payload the backend already parses.

## Decision

**1. Full-window re-read each sync (NOT anchor-advancing deltas).** Every sync reads
the last `syncWindowDays` (7) fully, assembles complete per-day records, and POSTs.
No anchors are advanced. This mirrors HAE's overlapping re-send and is **self-
healing**: a failed POST, a `days:0` response, an edit, or a deletion in the window is
corrected on the next sync; the backend upsert dedupes the re-sends. *(This replaced
an earlier anchored-delta design that lost data — deltas arrive piecemeal, so HRV read
without its sleep window assembled to nothing yet the anchor advanced past it. The
anchored reader is retained as a future incremental optimization but is not used by
v1 ingest — see the review-fixes note.)*

**2. A 2xx is not "stored".** The backend answers HTTP 200 `{ok:true, days:0}` when it
persists nothing (a revoked bearer is swallowed per-day). `post()` returns the stored-
day count and `sync()` treats `stored == 0` (with records sent) as a failure — surfaced,
not silently marked successful. Full-window re-read then retries next sync.

**3. Emit only non-nil fields.** The backend upserts per-field (last-write-wins), so a
sync that has only some of a day's metrics never nulls a fuller value. (Residual
ceiling: a sample *deleted* without replacement leaves its field omitted, so the backend
keeps the old value — delete-and-replace, the common case for RHR, is handled because
the replacement is in the re-read.)

**3. Payload matches the existing HAE contract exactly.** Metric names mirror
`HealthIngest.FIELD` (`heart_rate_variability`, `resting_heart_rate`,
`weight_body_mass`, `vo2max`, `respiratory_rate`,
`apple_sleeping_wrist_temperature`), plus a `sleep_analysis` record per night keyed
by `sleepEnd`. Units are chosen so the backend converters pass values through
unchanged (`kg`, `degC` with no "f", `ms`, `count/min`).

**4. Three triggers; foreground is the reliable one.** `HKObserverQuery` + hourly
`enableBackgroundDelivery` registered at app launch (App.init, per Apple), a
foreground catch-up on `scenePhase == .active`, and a manual "Sync now" in Settings.
Per the research, freshness is bounded by Garmin's sync, not us, so the
open-the-app foreground catch-up alone already beats HAE — background delivery is a
bonus. Observers always call their completion handler (`defer`), even on error, or
iOS disables delivery after three misses.

**5. Self-contained auth.** The sync reads the server URL (UserDefaults) and bearer
(Keychain) directly — the same places `AuthModel` writes them — so a background
wake-up works without a live view/`AuthModel`. It no-ops when signed out.

## Scope of this task (n1zt)

`HealthIngestClient` (pure payload builder + POST), `NativeHealthSync` (orchestrator
+ observer registration), and the app/Settings wiring. Payload building is unit-
tested (3 tests: name mapping + nil-omission, empty, gauge-only). The HAE "legacy"
Settings section stays for the migration overlap (removed in `ltfs`).

## Consequences

- **Device-only functional verification.** Background delivery and real anchored
  reads don't work in the Simulator — verified on-device via TestFlight, where the
  backend logs will finally show native `POST /api/health/ingest`. CI gate is the
  build + payload tests.
- **Redundant syncs are cheap and idempotent.** N observers can each fire a sync; a
  `syncing` guard drops concurrent runs, and re-sends upsert harmlessly.
- Partial-day multi-sync is safe via per-field upsert (decision 2); the one residual
  edge — a gauge whose samples split across syncs — averages per-sync then
  last-write-wins, close enough for v1.

## Review fixes (data-loss bugs the first cut shipped)

The initial delta-based design was reworked after review caught three silent data-loss
paths, all now fixed:
- **Anchor advanced past unsent data** — deltas assembled to nothing (HRV without its
  sleep window) still committed the anchor. Fixed by full-window re-read (decision 1).
- **HTTP 200 ≠ stored** — `days:0` on a revoked bearer advanced anchors past unstored
  data. Fixed by the stored-count check (decision 2) + no anchors.
- **One observer for all types** — an `observers.isEmpty` guard *inside* the per-type
  loop registered a single observer for an arbitrary type, so background delivery never
  woke the app for HRV/RHR/sleep. Fixed: the once-guard now wraps the whole loop, so
  there's one running `HKObserverQuery` per read type.

Also: the Settings spinner now binds to a published `isSyncing` (no duplicated state),
and the storage keys are shared from `AuthModel` (no drift).

## Alternatives considered

- *Anchored deltas + commit-after-confirm* — the original design; rejected after review:
  deltas arrive piecemeal so the assembler can't correlate sleep + gauges, and any
  advanced anchor is unrecoverable. Full-window re-read is simpler and self-healing.
- *POST raw samples, let the backend aggregate* — rejected: the backend expects
  daily summaries (esp. assembled sleep), not raw stage intervals.
