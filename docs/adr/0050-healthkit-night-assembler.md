# 0050 — HealthKit night assembler + first unit-test target (2026-07-21)

Date: 2026-07-21
Beans: iron-trainer-90iu (this task) · epic iron-trainer-2f2c
Related: ADR 0049 (reader layer — the input) · docs/research/native-healthkit-ingestion.md

## Context

The reader layer (ADR 0049) yields raw HealthKit samples. Health Auto Export used
to turn those into per-day recovery numbers server-side ("Summarize ON"); doing it
natively means assembling nights and rolling up gauges in the app before the
IngestClient (`n1zt`) POSTs. Sleep assembly is the intricate part — multiple
sources double-count, re-synced samples overlap — so it must be a pure, tested
transform, not something discovered on-device.

## Decision

**1. Wake-up-date night windowing via a +9h shift.** A sample belongs to the night
whose window is prev-day 15:00 → today 15:00. `startOfDay(start + 9h)` lands every
in-window sample on its wake-up date (15:00→next day, 14:59→same day) — one line, no
branching.

**2. One winning source per night; never merge across sources.** iPhone `inBed`
estimates, Apple Watch stages, and Garmin Connect stages otherwise triple-count. The
source with the most *unioned asleep* time wins the night; the others are dropped
whole (not merged).

**3. Union overlapping intervals per stage before summing.** Garmin re-syncs the
same night, producing overlapping samples; naive summation double-counts. A
half-open-interval union collapses overlaps so each stage's hours are real.

**4. `sleepH = deep+core+rem+unspecified`; `inBed` and `awake` excluded.** Matches
the recovery UI's model (core is derived), so the total never overstates sleep.

**5. Overnight gauges use the sleep window; day gauges use the calendar day.** HRV
(spot samples), respiratory rate, and wrist temp are meaned over the night's asleep
span (daytime HRV excluded); resting HR is the day's mean; body mass / VO₂ max take
the day's latest value.

**6. First unit-test target.** The project had `testTargets: []`. This adds
`IronTrainerTests` (host: IronTrainer, `@testable import`) with 6 tests covering the
windowing boundary, interval union, stage sums, source selection, sleep-window mean,
and daily gauges. `fastlane tests` now runs them.

## Scope of this task (90iu)

Pure transform + tests only. No HealthKit calls (reader's job), no POST/observers
(`n1zt`). `assemble(sleep:quantities:)` → `[DailyRecovery]`.

## Consequences

- **Testable without a device** — the assembler is pure, so its correctness is CI-
  gateable (unlike the reader/ingest, which need real HealthKit).
- HRV needs an Apple Watch (Garmin doesn't write HRV) — empty is a valid result.

## Accepted ceilings (from code review — deliberately not fixed in v1)

- **Naps / sessions straddling 15:00.** v1 buckets each sleep sample by its start and
  unions everything in the night window; a long afternoon nap before 15:00, or a
  session split across the 15:00 boundary, folds into that night. Nocturnal sleep
  doesn't straddle 15:00, so this only affects unusual nap patterns. The research's
  `<2h`-gap sessionization is the fix and is deferred.
- **Gauge day-key vs sleep wake-date.** Calendar-day gauges (RHR, body mass, VO₂) key
  by `startOfDay(measurement)`; sleep keys by wake-date (`start+9h`). For a
  morning-centric routine both resolve to the same calendar date D, giving a coherent
  "day D" record — this is the intended daily model, matching the backend's
  date-keyed rows, not a misalignment.
- **Gauge-only days are emitted.** A weigh-in on a day with no sleep tracking produces
  a record with only that gauge — valid data we intentionally keep, not a phantom.

## Review fixes applied

Deterministic winner tie-break (score each source once, break ties by bundle id);
overnight gauge means restricted to a single dominant source (no cross-calibration
blend) and use interval-overlap window membership (respiratory/wrist-temp are
intervals); added multi-night, tie-determinism, empty-input, and straddle tests
(10 total).

## Alternatives considered

- *Let the backend assemble from raw intervals* — rejected: the ingest contract is
  daily summaries (HAE shape); the backend doesn't stage-assemble, and the
  per-source dedup logic belongs where the source ids are known.
- *Sum stage samples directly* — rejected: double-counts re-synced overlaps.
- *Skip the test target* — rejected: this is the epic's most error-prone logic and
  it's pure; a test target is the right tool, not scaffolding.
