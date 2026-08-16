---
# iron-trainer-m4vq
title: Race predictor ignores FTP — bike leg has no threshold input
status: completed
type: feature
priority: normal
created_at: 2026-08-16T00:50:23Z
updated_at: 2026-08-16T23:25:57Z
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
see HealthResource.ingest — deferred to bean 30m8 pending a source-of-truth
policy. If that lands, three inputs write FTP (test-applied, manual, Apple
estimate) and precedence must be decided first.

## Summary of Changes

Implemented a **fourth option**, not one of the three listed above — ADR-0067. None of the options as written was taken: option 1 was too little, and options 2 and 3 both rest on an absolute FTP→speed model needing guessed CdA/Crr. What shipped instead keeps measured speed as the anchor at ALL times and uses FTP only as an intensity RATIO, which cancels those constants outright. Built in worktree `ftp-race-intensity-correction`.

**RaceReadiness.java** — `recentBikeSpeed(activities)` → `recentBikeSpeed(activities, th, distance)` returning a `BikeSpeed(speedMs, basis)` record. Scales observed long-ride speed by `cbrt(P_race / P_observed)` using AVERAGE power (never normalized — see below) where `P_race = RACE_IF[distance] x ftp` (0.78 for 70.3, 0.70 for 140.6). The ratio form cancels CdA/Crr/mass — no physical constants assumed. Correction is computed over the power-bearing subset only; falls back to the previous uncorrected mean when FTP or ride power is absent. Scale clamped to [0.85, 1.25]. Bike leg gained `basis`; `note` names the race intensity.

**Frontend** — `Leg.basis?`; `.rd-basis` caption under the splits saying whether the split is race-scaled or raw training pace.

**docs/adr/0066** + README index row.

## Verification

8 new `RaceReadinessTest` cases (exact scaling, scales DOWN for hard rides, powerless rides excluded, 140.6 intensity, clamp, null, basis reported). Full suite 263 passed / 0 failed. Frontend typechecks + builds.

## Deliberately not done

No absolute FTP→speed model for the no-ride-history case — that is the one case needing guessed CdA/Crr. No run-leg change. No auto-seed of `Athlete.ftp` from `daily_recovery.cycling_ftp_w` (bean 30m8 — NOT mg1n, which shipped the capture; 30m8 is the auto-seed follow-up split out of it, and already documents the latest-by-timestamp / seed-bounds / delta-sync-clobber problems in detail).

## Correction after review (PR #117)

Copilot caught a real bug in the first cut: it preferred `weightedPower` (normalized) over `avgPower` for `P_observed`. NP is a physiological load metric, not the mean mechanical power that produced `avg_speed`, and NP >= AP always — so on a variable ride (160 W avg / 220 W NP) the correction could FLIP from scaling up to scaling down. Now uses `avgPower` only; a ride carrying only NP sits out the correction rather than being mis-scaled.

Related assumption now documented in code: `RACE_IF` is quoted as normalized power (IF = NP/FTP) but compared against an average, which holds only because a well-paced race leg has VI ~ 1.0. Degrades optimistic for a surging rider on a hilly course.

Also from that review: `.rd-basis` moved from `--dim` to `--muted` (WCAG AA at 12px).
