# ADR 0012 — iOS "Today" release: Today view, widgets, Liquid Glass

**Status:** Accepted · 2026-07-08

## Context

The iOS app was a "plan sync remote": pair → flat workout list → push to Watch.
Three UI/UX directions were chosen in a brainstorm to make it a daily companion:
a Today home screen, WidgetKit widgets, and a Liquid Glass design pass. The
enabling discovery: the `.itw` payload already carries `raceName`/`raceDate`
(previously decoded and discarded) plus per-step targets and athlete thresholds,
so everything ships client-side with zero backend changes.

## Decisions

### Today view as the loaded-plan home
`.loadedPlan` now renders `TodayView` — race countdown header, today's session
as a hero card (interval profile, fueling line, one-tap Send to Watch), rest-day
state, tomorrow peek, and a value-based `NavigationStack` push to the existing
`WorkoutListView` (whose select→preview→schedule flow is untouched). The hero's
Send to Watch is **local state**, not `ImportModel.schedule` — routing through
the global state machine would flip to `.scheduled` and dismiss the Today screen.

### Pure-SwiftUI profile chart (no Swift Charts)
Variable-width interval bars need hand-computed offsets in Swift Charts anyway;
a GeometryReader + bottom-aligned HStack is ~40 lines, renders identically in
the widget extension, and keeps the extension lean. Intensity per step: power →
mid/FTP; HR → mid/LTHR; pace → threshold/mid (inverted — faster than threshold
exceeds 1.0; CSS for `sec_per_100m`, run threshold for `sec_per_km`); open or
missing threshold → fixed low bar in a neutral tint. Display clamp 0.2–1.2.

### Shared code via a `Shared/` source folder, not a framework
`ios/Shared/` (model, intensity math, chart, snapshot, App Group store, fuel
parser) is compiled into both targets by XcodeGen. A shared framework would be
cleaner in a large app; for ~7 files it's pure overhead. `ItwWorkout.swift`
moved from `IronTrainer/Model/` into `Shared/`.

### Widgets read a precomputed snapshot, never the model
The app writes `snapshot.json` (race meta + 7 days, fueling parsed, profiles
precomputed) to App Group `group.io.gamov.irontrainer` on every successful plan
load, then reloads timelines. The extension holds no threshold math, no
networking, no `ItwWorkout`. Timeline = now + one entry per upcoming local
midnight (≤7), `.atEnd` — the countdown decrements and "today" rolls over with
at most ~1 reload/week if the app is never opened.

### Staleness posture: degrade honestly, no background sync
Past the 7-day snapshot horizon the workout widget shows "Open Iron Trainer to
sync"; the countdown widget keeps counting from the fixed race date. This
matches the app-wide no-silent-background-sync posture (ADR 0008/0011). A
`BGAppRefresh` follow-up would be its own ADR.

### Liquid Glass scope
Glass on the navigation layer and primary actions only, per HIG: brand-orange
(`#ff5d3b`, the web `--accent`) `.glassProminent` CTAs on iOS 26 with tinted
`.borderedProminent` fallback (deployment target stays 18.0); content cards use
`.regularMaterial`/`.thinMaterial`, which auto-adapt on 26. Sport colors/icons
unified in `SportStyle` (matching the web charts); missing toolbar
accessibility labels added.

## Consequences

- New target `IronTrainerWidgets` (`io.gamov.irontrainer.helper.widgets`) and an
  App Group entitlement on both binaries. Automatic signing
  (`-allowProvisioningUpdates` + ASC API key) is expected to register both; if
  the key lacks rights, one-time manual registration in Xcode/portal.
- `PlanNetworkSource.loadPlan()` now returns `TrainingPlan` (meta + workouts);
  `ImportModel.State.loadedPlan` carries it.
- Known edge (pre-existing, surfaced during sim testing): `AuthModel.signIn`
  reports success even if the Keychain write fails (only observable on unsigned
  builds). Left as-is; tracked for a future auth pass.

## Verification

- `xcodegen generate` + simulator builds of both targets: clean.
- End-to-end on a simulator against a seeded local backend (72-workout template
  plan): deep-link pairing → Today view renders countdown (80 days), run hero
  with 3-segment profile, tomorrow's swim, 72-workout link; fueling line
  correctly absent on a sub-45-min session. Screenshot in PR.
- App Group verified: `snapshot.json` present in the group container with race
  meta, 7 days (rest days as empty), and precomputed profile segments.
- TestFlight cut after merge for on-device: widgets (home + lock screen),
  Send to Watch, widget refresh after in-app reload.
