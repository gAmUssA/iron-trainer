# Iron Trainer — iOS helper app

A small SwiftUI app (iOS 18+) that turns an **Iron Trainer Workout (`.itw`)** file
into a native Apple workout: it imports the file, builds a WorkoutKit
`CustomWorkout` / `WorkoutPlan` on-device, lets you preview it, and **schedules it
to its planned date** so it syncs to the Workout app on your Apple Watch.

## Why a helper app (and what `.itw` is)

Apple's native workout file is the binary **`.workout`** format produced by
WorkoutKit's `WorkoutComposition`. That format is **undocumented and can only be
generated on-device** by the framework — a server cannot create one. So the Iron
Trainer backend exports a neutral JSON file with the `.itw` extension
(`backend/app/export/itw_export.py`), and this app builds the real Apple workout
from it on the device.

## Build

This folder uses [XcodeGen](https://github.com/yonik/XcodeGen) so the project is
reproducible without committing a `.pbxproj`:

```sh
brew install xcodegen          # once
cd ios
xcodegen generate             # creates IronTrainer.xcodeproj
open IronTrainer.xcodeproj     # Xcode 16+ (iOS 18 SDK)
```

Then set your **Team ID** (`DEVELOPMENT_TEAM` in `project.yml`, or in Xcode's
Signing & Capabilities) and add the **WorkoutKit** and **HealthKit** capabilities
(already declared in `Resources/IronTrainer.entitlements`).

> WorkoutKit initializer signatures have shifted across SDK seeds. If the project
> doesn't compile against your SDK, the place to adjust is
> `WorkoutKit/ItwToWorkoutKit.swift` (alert/goal/block initializers).

## Project layout

```
ios/
  project.yml                       # XcodeGen spec
  IronTrainer/
    App/        IronTrainerApp.swift, ImportModel.swift
    Model/      ItwWorkout.swift          # Codable mirror of the .itw schema
    Import/     WorkoutSource.swift       # FileImportSource (MVP) + NetworkSource (stub)
    WorkoutKit/ ItwToWorkoutKit.swift     # .itw -> CustomWorkout/WorkoutPlan
                WorkoutScheduling.swift   # authorize + schedule(at:)
    Views/      ContentView.swift, WorkoutPreviewView.swift
    Resources/  Info.plist (UTI + doc type), entitlements, PrivacyInfo.xcprivacy
```

## How it works (data flow)

1. **Backend** → `GET /api/export/workout/{id}.itw` returns versioned JSON
   (`schema_version: 1`) with sport, date, steps, targets, and athlete thresholds.
2. **Import** → open the `.itw` from Files / Mail / AirDrop (it's registered to
   this app via the exported UTI), or pick it with the in-app importer.
   `ItwWorkout.decode` version-gates the file.
3. **Build** → `ItwToWorkoutKit` maps steps to a `CustomWorkout` (warmup,
   one interval block, cooldown) with power/HR/pace alerts.
4. **Schedule** → `WorkoutScheduling.schedule` asks authorization and calls
   `WorkoutScheduler.shared.schedule(plan, at:)` on the planned date.

## Roadmap

- **MVP (now):** file import → preview → schedule to date.
- **Next:** "Open in Workout app" action; richer interval repeats; direct
  network fetch via `NetworkSource` once in-app login exists (the API shape is
  already stubbed).
