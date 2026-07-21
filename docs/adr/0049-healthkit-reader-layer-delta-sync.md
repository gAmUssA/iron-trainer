# 0049 — HealthKit reader layer: anchored delta-sync contract (2026-07-21)

Date: 2026-07-21
Beans: iron-trainer-aw8t (this task) · epic iron-trainer-2f2c
Related: ADR 0048 (auth foundation) · docs/research/native-healthkit-ingestion.md ·
axiom-health/sync-and-background (the canonical patterns this follows)

## Context

With HealthKit read access granted (ADR 0048), the next layer reads recovery
samples. Re-reading the whole store each launch is the wrong design (battery,
misses deletions, duplicates) — Apple's anchored-object model exists to avoid it.
This task builds the reader mechanics; aggregation and delivery are later tasks.

## Decision

**1. Anchored delta reads via the modern descriptor.** One
`HKAnchoredObjectQueryDescriptor.result(for:)` per type, from a persisted
`HKQueryAnchor`. First read (nil anchor) returns full history; subsequent reads
return only adds + deletes since the anchor. We use the iOS 15.4+ descriptor form
(we target iOS 18) rather than the callback `HKAnchoredObjectQuery`.

**2. Anchor is committed only after the server confirms ingest.** `readQuantity`/
`readSleep` return a *candidate* `newAnchor` but do NOT persist it; the caller
(IngestClient, `n1zt`) calls `commit(anchor:for:)` after a successful POST. A
failed POST re-reads the same delta next time — at-least-once delivery, made safe
by the backend's idempotent upsert. This is why the read and the commit are split.

**3. Readers emit raw, unit-normalized samples; no aggregation.** Each quantity
sample is extracted in its canonical unit (HRV ms, HR/resp per-minute, temp °C,
mass kg, VO₂ mL/kg·min) with its source bundle id; sleep samples keep every stage
raw. The sleep-window HRV mean, RHR yesterday-recompute, and night assembly all
live in the NightAssembler (`90iu`) — the reader must not pre-aggregate, or the
assembler can't apply its per-source, sessionize-and-union rules.

**4. Deletions are surfaced, not ignored.** Every delta carries
`deletedUUIDs` (from `HKDeletedObject`). Critical for resting HR, which Apple
delete-and-replaces as estimates improve — dropping deletions would leave stale
RHR values.

**5. Anchors in standard `UserDefaults`, keyed by type identifier.** Archived with
`NSKeyedArchiver`/secure coding. App-internal (the widget never reads them), so no
App Group needed — unlike the widget snapshot.

## Scope of this task (aw8t)

Reader layer only: `AnchorStore`, `HealthKitReader`, and the typed sample models.
No POST, no observers/background delivery (`n1zt`), no aggregation (`90iu`), no UI.
Nothing observable ships this build; it compiles and is exercised on-device once
`n1zt` wires it to the ingest endpoint.

## Consequences

- **Device-only real verification.** Anchored queries need real HealthKit data; the
  simulator has none. Gate is the compile (`BUILD SUCCEEDED`); functional check
  rides `n1zt`'s beta.
- **Long-absence deletions may be missed** — `HKDeletedObject`s are transient
  (Apple may purge them). Acceptable: additions still arrive; a full resync is the
  fallback if strict correctness ever matters.

## Alternatives considered

- *Persist the anchor inside the reader on every read* — rejected: decouples anchor
  advance from ingest success, so a failed POST would skip data permanently.
- *Callback `HKAnchoredObjectQuery`* — rejected: the async descriptor is cleaner and
  fine at our deployment target.
- *Aggregate in the reader* — rejected: the assembler needs raw per-source stages.
