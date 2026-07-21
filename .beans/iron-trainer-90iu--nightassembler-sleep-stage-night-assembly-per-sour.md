---
# iron-trainer-90iu
title: NightAssembler — sleep-stage night assembly (per-source, sessionize, union)
status: todo
type: task
priority: normal
created_at: 2026-07-21T15:41:54Z
updated_at: 2026-07-21T17:28:07Z
parent: iron-trainer-yrsz
---

The part HAE did for us. Window prev-day 15:00 → today 15:00; group by source bundle id; NEVER merge stages across sources (iPhone inBed + Watch + Garmin double-count). Pick one winning source/night (user override → longest-asleep stage-writer). Sessionize with <2h gap merge; union overlapping intervals per stage before summing (Garmin re-syncs overlap). totalSleep = core+deep+rem(+unspecified), inBed excluded. Key by (source, wake-up date). Garmin 'light' ≈ Apple asleepCore — verify raw values on-device (5-min debug query).

## Summary of Changes (2026-07-21)
Pure sleep/gauge assembler: raw reader samples -> [DailyRecovery]. No HealthKit calls / no POST (n1zt).
- nightDate via +9h shift (prev 15:00→15:00 window → wake-up date).
- Per-night winning source by unioned-asleep time; other sources dropped whole (never merged — avoids iPhone/Watch/Garmin triple-count).
- unionSeconds: half-open interval union per stage (Garmin re-sync dedup).
- sleepH = deep+core+rem+unspecified; inBed/awake excluded.
- Overnight gauges (HRV/resp/wristTemp) = mean over the night's sleep window; day gauges (RHR mean, bodyMass/VO2 latest) by calendar day.
- FIRST unit-test target (IronTrainerTests, @testable): 6 tests (windowing boundary, union, stage sums, source winner, sleep-window HRV mean, daily gauges) — ALL PASS (xcodebuild test, iPhone 16 sim). fastlane tests now runs them.
- ADR 0050. Naps (<2h sessionize) deferred, noted as ceiling.
Fixed a compile error (multiple-trailing-closure + inout arg → full-paren call).
