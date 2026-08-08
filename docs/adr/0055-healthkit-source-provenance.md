# 0055 — HealthKit recovery source provenance (2026-08-08)

- **Status:** Accepted
- **Bean:** aydv (child of WHOOP epic ids6)

## Context

WHOOP writes raw metrics (HRV, RHR, sleep, …) *into* Apple Health, so our native
HealthKit pipeline already ingests WHOOP-originated samples for any user who syncs
WHOOP → Apple Health. The `NightAssembler` already picks one winning source per
metric (never blends) for sleep + overnight gauges — so cross-wearable
double-counting was already handled. But the winning source's **identity was
dropped** before the backend, so readiness couldn't be labeled by origin. That
matters for the planned WHOOP overlay: if the HealthKit HRV is itself WHOOP-sourced,
"Iron Trainer Readiness" vs "WHOOP Recovery" is WHOOP-vs-WHOOP, not a genuine
two-source comparison.

## Decision

- **Persist the recovery source** (the HRV winner's `sourceBundleID`, the recovery
  driver and exactly what WHOOP writes) end-to-end: iOS `DailyRecovery.source` →
  `source` on the HRV data point in the ingest payload → `daily_recovery.source`
  (V5 migration, nullable/additive) via `HealthIngest` + `HealthResource`.
- **Complete the "never blend sources" invariant:** `applyDaily` now picks the
  dominant source per day for the `average` reducer (RHR) instead of averaging across
  sources — but the `latest` reducer (bodyMass/vo2Max) keeps taking the globally
  newest reading regardless of source (isolating a *latest* would drop a fresher
  reading from another device — caught in review).

## Alternatives considered

- **Per-metric source columns** — overkill; the recovery signal (HRV) is the one that
  matters for the overlay, so a single `source` per day suffices.
- **Rely on HealthKit source-tagging only (no direct WHOOP API)** — deferred to the
  WHOOP epic; provenance here is the foundation either way.

## Consequences

- The WHOOP overlay can label readiness by origin → a real two-source comparison.
- V5 applied **out-of-order** in prod (V6 WHOOP journal was already live);
  `flyway.out-of-order=true` (added for the parallel WHOOP branches) let it apply.
- HealthKit HRV is SDNN while the WHOOP API reports RMSSD — never merge across them.

## Verification

iOS 16 unit tests (HRV-source capture, RHR dominant-source, bodyMass global-latest —
no regression); backend `HealthIngestTest` (source parsed on HRV only). Deploy
verified: `daily_recovery.source` exists in prod after the out-of-order migration.
