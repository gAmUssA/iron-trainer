# 0003 — Apple Workouts export via `.itw` + WorkoutKit iOS helper app

- **Status:** Accepted (retroactive — PR #1)
- **Date:** 2026-06-26
- **Deciders:** Viktor + Claude

## Context

Structured workouts were exported as `.fit` (Garmin) and `.zwo` (TrainingPeaks).
But TrainingPeaks' Workout Library import **only accepts power-based bike `.zwo`** —
not `.fit`, and not run/swim at all (confirmed against TP docs and the file picker
graying out `.fit`). So there was no clean path to get structured swim/bike/run
workouts onto an **Apple Watch**. Apple's native workout file (`.workout`) is an
undocumented binary that can only be produced **on-device** by WorkoutKit — a server
cannot generate it.

## Decision

The backend emits a neutral, versioned **`.itw`** file (Iron Trainer Workout — JSON),
and a small native **iOS helper app** turns each workout into a WorkoutKit
`CustomWorkout` and **schedules it to its planned date** on the Apple Watch.

- **Transport = file/Share import (MVP).** Web download → open in the helper app via
  Share/Files/AirDrop. No login in the app; one JSON export endpoint. (Live API
  fetch was deferred — see [0004](0004-device-pairing-live-sync.md).)
- **iOS 18+ minimum** — WorkoutKit custom **pool-swim** workouts require iOS 18, so
  swim/bike/run are all structured.
- **Schedule to planned dates** via `WorkoutScheduler.shared.schedule(plan, at:)`.
- Mapping: warmup → cooldown with interval blocks between; `duration_s`/`distance_m`
  → `.time`/`.distance`/`.open` goals; power→watts range, pace→speed range, hr→bpm.

## Alternatives considered

- **Generate `.workout` server-side** — impossible; it's on-device-only via WorkoutKit.
- **Push everything through TrainingPeaks** — rejected: TP import is bike-`.zwo` only;
  can't carry run/swim. `.fit` → Garmin Connect remains the path for those; `.itw` →
  Apple Watch is the new path.
- **In-app login + live fetch first** — deferred to keep MVP tiny (file import only);
  the API is cookie-session only, so a native auth path was non-trivial.

## Consequences

- (+) First clean route to structured workouts on Apple Watch, all three sports.
- (+) `.itw` is a stable, versioned contract decoupling backend from the app.
- (−) Needs a real iPhone + Apple Watch and an Apple developer account; scheduling
  can't be tested in the Simulator (verified import/preview there, scheduling on device).
- (−) WorkoutKit scheduling is limited to **±7 days** and **15 scheduled at a time**.
- **`UISupportedInterfaceOrientations`** and `LSSupportsOpeningDocumentsInPlace` are
  required for App Store validation (90474/90737).
- **WorkoutKit needs no dedicated entitlement** — only HealthKit + a runtime
  `WorkoutScheduler` authorization. A bogus `com.apple.developer.workout-kit`
  entitlement broke automatic provisioning until removed.
- Build/release is XcodeGen + fastlane (`fastlane beta` → TestFlight). Several
  environment fixes were needed (rsync 3.4 vs openrsync export conflict, shared
  scheme, agvtool versioning) — captured in commit history.

## Implementation notes

Backend: `app/export/itw_export.py`, `service.py`, `routers/export_router.py`
(`/api/export/workout/{id}.itw`). iOS: `ios/` (XcodeGen + fastlane) —
`ItwWorkout` model, `ItwToWorkoutKit`, `WorkoutScheduling`, date-picker preview with
±7-day window (clamps out-of-range planned dates instead of dead-ending).

## Verification

`tests/test_export.py` (.itw schema + steps round-trip). iOS built for sim+device;
import→preview verified on simulator; full schedule→Watch verified on-device via a
TestFlight build. Shipped in PR #1.
