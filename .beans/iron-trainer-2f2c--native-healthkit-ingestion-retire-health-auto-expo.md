---
# iron-trainer-2f2c
title: Native HealthKit ingestion — retire Health Auto Export
status: todo
type: epic
priority: high
created_at: 2026-07-21T15:41:27Z
updated_at: 2026-07-21T15:41:27Z
---

Iron Trainer's own iOS app reads recovery + workout data directly from HealthKit and pushes to the existing POST /api/health/ingest protocol, removing the third-party Health Auto Export dependency ($25 Premium paywall, unguaranteed delivery, manual per-metric setup).

Research complete (2026-07-15): docs/research/native-healthkit-ingestion.md. Verdict: BUILD IT — ~500-800 LOC Swift, ZERO backend changes (emit the same HAE-shaped payload). HAE + native can run simultaneously during migration; backend upserts dedupe. Equal-or-better delivery (freshness ceiling is Garmin's sync, not us; our app opens regularly → foreground catch-up alone beats HAE).

## Scope
- v1 (feature [[iron-trainer-yrsz]]): sleep stages, HRV (sleep-window mean of spot samples), resting HR (delete-and-replace → HKDeletedObjects), respiratory rate, wrist temp, body mass. Delivered via 4 modules: HealthKitAuthorizer · MetricReaders (per-type anchors) · NightAssembler · IngestClient. Settings "Connect Apple Health" card.
- v2 (feature [[iron-trainer-yj6a]]): HealthKit workouts[] as a Strava-independent activity source (HR streams, HR recovery, GPS routes). Garmin caveat: no GPS route / power via Health yet → can't fully replace Strava for bike power.
- Adjacent (feature [[iron-trainer-30m8]]): FTP→bike-zones auto-seed from Apple's cycling FTP estimate, done safely.

## Constraints (from research)
- Entitlements: healthkit + healthkit.background-delivery (NOT clinical records). NSHealthShareUsageDescription required.
- Read-denial is INVISIBLE (authorizationStatus reflects writes only) → NEVER gate on status; empty states point to Health → Sharing.
- Guideline 5.1.3: privacy policy mandatory (must cover health data); App Privacy label Health & Fitness, linked to identity, no tracking.
- Background delivery doesn't work in Simulator → device test (TestFlight; WidgetKit-style Viktor-driven).
- HRV stays empty without an Apple Watch (Garmin doesn't write HRV to Health) — UI must say so.

## Migration
2-4 week HAE overlap, then user deletes HAE. Backend dedup makes double-ingest safe.
