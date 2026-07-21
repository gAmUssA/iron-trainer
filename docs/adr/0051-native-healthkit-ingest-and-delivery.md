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

**1. Delta-read → assemble → POST → commit anchors on success.** Each sync reads
anchored deltas for all recovery types, assembles them into per-day records, POSTs,
and only then advances the anchors. A failed POST leaves anchors un-advanced, so the
same delta re-sends next trigger — at-least-once; the backend upsert dedupes.

**2. Emit only non-nil fields.** The backend upserts per-field (last-write-wins), so
a partial sync (a delta covering only some of a day's metrics) never nulls a fuller
value written by an earlier sync. This is what makes delta-based sync correct without
a local sample mirror — the payload builder omits nil fields, and a night's sleep
samples arrive atomically (post-wake) so the sleep record is complete in one POST.

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

## Alternatives considered

- *Local sample mirror + full daily re-aggregate* — rejected for v1: more state; the
  per-field-upsert + atomic-sleep-write properties make delta-POST correct enough.
- *POST raw samples, let the backend aggregate* — rejected: the backend expects
  daily summaries (esp. assembled sleep), not raw stage intervals.
