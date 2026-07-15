# Native HealthKit ingestion — replacing Health Auto Export

Date: 2026-07-15 · Bean: iron-trainer-yrsz · Verified against developer.apple.com
(July 2026), Garmin support/forums, community integrations.

## Verdict

Build it: ~500-800 lines of Swift, zero backend changes (emit the same
HAE-compatible payload to POST /api/health/ingest), equal-or-better delivery
than Health Auto Export, no $25 dependency, no manual setup. HAE and native
sync can run simultaneously during migration — the backend upserts dedupe.

## Types (all verified current)

- Sleep: HKCategoryType(.sleepAnalysis); stage values .inBed(0),
  .asleepUnspecified(1), .awake(2), .asleepCore(3 = "light"), .asleepDeep(4),
  .asleepREM(5) — all fine at our deployment target. Use allAsleepValues in
  predicates.
- .heartRateVariabilitySDNN (ms) — Watch writes SPOT samples several times a
  day, not one daily value → send mean of samples in the sleep window.
- .restingHeartRate (bpm) — Apple DELETES AND REPLACES today's/yesterday's
  sample as estimates improve → process HKDeletedObjects, recompute yesterday.
- .respiratoryRate, .appleSleepingWristTemperature (READ-ONLY, Apple
  Watch-only), .bodyMass, .vo2Max (Garmin does NOT sync VO2max to Health).

## Night assembly (the part HAE did for us)

Window prev-day 15:00 → today 15:00; group by source bundle id; NEVER merge
stages across sources (iPhone .inBed estimates + Watch stages + Garmin
Connect stages double-count). Pick one winning source per night (user
override → stage-writer with longest asleep). Sessionize with <2h gap merge;
union overlapping intervals per stage before summing (Garmin re-syncs
overlap). totalSleep = core+deep+rem(+unspecified); inBed excluded. Key by
(source, wake-up date).

## Delivery architecture

HKObserverQuery + enableBackgroundDelivery (.hourly; entitlement
com.apple.developer.healthkit.background-delivery) → anchored query with
persisted HKQueryAnchor per type → POST → advance anchor ONLY after server
confirms (at-least-once; backend upsert = idempotent) → ALWAYS call observer
completion (3 misses = iOS disables delivery). Plus: foreground catch-up on
scenePhase.active and a manual Sync-now row. Observers registered in App
init. Background delivery doesn't work in Simulator — device test.

Freshness ceiling is GARMIN's sync (Garmin Connect writes to Health only
when it syncs), not Apple. Our app is opened regularly → foreground catch-up
alone beats HAE's practical reliability.

## Garmin caveats

- Sleep stages: YES since Garmin Connect iOS 4.71 (2023); "light" ≈ Apple
  .asleepCore. Verify raw values on-device (5-min debug query).
- RHR: YES. **HRV: NO** — Garmin doesn't write HRV to Health (and Garmin
  HRV = overnight RMSSD vs Apple SDNN). No regression vs HAE (same source
  data), but HRV stays empty without an Apple Watch. UI must say so.
- Workouts (v2): Garmin writes summaries + HR but NOT GPS routes, and power
  sync unconfirmed-likely-missing → HealthKit can't fully replace Strava for
  bike power yet. cyclingPower type exists since iOS 17. Architect v2 as
  another anchored module; don't block v1.

## Permissions / review

Entitlements: healthkit + background-delivery (NOT clinical records).
NSHealthShareUsageDescription required. Read-denial is INVISIBLE by design
(authorizationStatus reflects writes only) → design empty states pointing to
Health → Sharing; never gate on status. Guideline 5.1.3: privacy policy
mandatory (must cover health data); App Privacy label: Health & Fitness,
linked to identity, no tracking. Request permission in context (Settings →
"Connect Apple Health" card with last-sync + per-metric freshness).

## v1 modules

HealthKitAuthorizer · MetricReaders (per-type anchors) · NightAssembler ·
IngestClient (HAE-shaped payload, bearer). Settings card. 2-4 week HAE
overlap, then user deletes HAE.
