---
# iron-trainer-m4vq
title: Race predictor ignores FTP — bike leg has no threshold input
status: completed
type: feature
priority: normal
created_at: 2026-08-16T00:50:23Z
updated_at: 2026-08-16T19:03:20Z
---

RaceReadiness.raceReadiness derives the bike split ONLY from recentBikeSpeed() — mean avg_speed over rides >= 1h in the last 84 days. `ftp` is never read.

Consequences:
- Applying a 20-min FTP test cannot move the projected bike split or finish time (it moves current_ctl only, via the TSS recompute).
- An athlete with no long rides in 84 days has a permanently missing bike leg and no way to supply one by hand.
- ProfileEditor copy claims thresholds "drive TSS, training zones and the race projection" — true for css_swim and threshold_pace_run, false for FTP.

## Options
1. Leave as-is; measured speed beats modelled speed when history exists. Just fix the copy (the missing-leg banner is already fixed in uyqq).
2. FTP fallback ONLY when there is no ride history: race power ~= 0.75 x FTP for 70.3 (0.70 for 140.6), then speed from a simple CdA + Crr model needing body_weight_kg (already on the profile) plus assumed constants.
3. Blend: measured speed when present, FTP-modelled otherwise, and show which one produced the split.

Options 2/3 introduce physical constants that need a calibration knob — do not ship them as hidden magic numbers.

## Data availability (checked 2026-08-16)

No capture change needed. `Dedup.scoreComparator` for Bike ranks
`isBikeComputer > hasPower > hasHr > length`, so the Garmin Edge copy wins any
same-event cluster, and `hasPower` is the explicit second tiebreak even when the
device name is unknown. `RaceReadinessResource` already reads only
`isDuplicate = 0 or isDuplicate is null` — i.e. exactly the power-bearing winners.

Residual gap: a SOLO Apple Watch ride (no Edge copy → no cluster → nothing to
prefer) can lack power. Handle by averaging the intensity correction over the
power-bearing subset only and falling back to the uncorrected average when that
subset is empty. Reuse `Dedup.hasPower`-equivalent predicate; don't branch on
device name.

Third FTP source already in the DB and deliberately parked:
`daily_recovery.cycling_ftp_w` (Apple's own FTP estimate via Health Auto Export),
see HealthResource.java:163 — deferred to bean mg1n pending a source-of-truth
policy. If that lands, three inputs write FTP (test-applied, manual, Apple
estimate) and precedence must be decided first.

## Summary of Changes

Implemented **option 3** (measured speed anchored, FTP as intensity correction) — ADR-0066. Built in worktree `ftp-race-intensity-correction`.

**RaceReadiness.java** — `recentBikeSpeed(activities)` → `recentBikeSpeed(activities, th, distance)` returning a `BikeSpeed(speedMs, basis)` record. Scales observed long-ride speed by `cbrt(P_race / P_observed)` where `P_race = RACE_IF[distance] x ftp` (0.78 for 70.3, 0.70 for 140.6). The ratio form cancels CdA/Crr/mass — no physical constants assumed. Correction is computed over the power-bearing subset only; falls back to the previous uncorrected mean when FTP or ride power is absent. Scale clamped to [0.85, 1.25]. Bike leg gained `basis`; `note` names the race intensity.

**Frontend** — `Leg.basis?`; `.rd-basis` caption under the splits saying whether the split is race-scaled or raw training pace.

**docs/adr/0066** + README index row.

## Verification

8 new `RaceReadinessTest` cases (exact scaling, scales DOWN for hard rides, powerless rides excluded, 140.6 intensity, clamp, null, basis reported). Full suite 263 passed / 0 failed. Frontend typechecks + builds.

## Deliberately not done

No absolute FTP→speed model for the no-ride-history case — that is the one case needing guessed CdA/Crr. No run-leg change. No auto-seed of `Athlete.ftp` from `daily_recovery.cycling_ftp_w` (bean mg1n); if that lands, three writers contend for `Athlete.ftp` with no precedence rule — settle that first.
