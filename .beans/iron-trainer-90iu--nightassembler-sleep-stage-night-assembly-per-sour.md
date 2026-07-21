---
# iron-trainer-90iu
title: NightAssembler — sleep-stage night assembly (per-source, sessionize, union)
status: completed
type: task
priority: normal
created_at: 2026-07-21T15:41:54Z
updated_at: 2026-07-21T18:29:50Z
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

## Code review fixes (2026-07-21)
High-effort review: 10 distinct defects. Fixed the correctness cluster, documented accepted ceilings.
FIXED: [0/6] nondeterministic winner tie-break + O(n²) recompute → score each source once, deterministic (asleep,src) max. [1] overnight gauge blended sources → single dominant source (most in-window samples). [2] gauge dropped straddling interval samples → interval-overlap window test (end>=lo && start<=hi). [7/8] added multi-night, tie-determinism, empty-input, straddle tests → 10 tests, 0 failures.
ACCEPTED CEILINGS (documented in ADR 0050): [3] session-straddle-15:00 = nap sessionization family (deferred; nocturnal sleep doesn't straddle). [4] gauge calendar-day vs sleep wake-date = intended daily model (both → date D for morning routine). [5] gauge-only days = valid data, kept. [9] latest ?? 0 = harmless, grouping guarantees non-empty.
