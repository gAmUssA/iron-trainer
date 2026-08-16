---
# iron-trainer-uyqq
title: Threshold/FTP data doesn't bubble up from Tests tab
status: completed
type: bug
priority: high
created_at: 2026-08-16T00:46:46Z
updated_at: 2026-08-16T01:57:49Z
blocking:
    - iron-trainer-m4vq
---

Applying a fitness test (FTP / LTHR / CSS) in the Tests tab does not propagate the
way a Settings → Thresholds edit does, and the race predictor never reacts to FTP.

## Traced data flow

Settings → Thresholds (PUT /api/athlete/profile → ProfileResource.updateProfile):
  save_profile → recompute_tss (all activities) → rebuild_metrics (PMC)
  → PlanTargets.refreshFuture()  ← re-derives FUTURE workout targets from ftp/pace/css

Tests tab → Apply (POST /api/tests/result/{id}/apply → FitnessTestsResource.applyResult):
  save_profile → recompute_tss → rebuild_metrics
  → (nothing)                    ← MISSING the plan-target refresh

`PlanTargets.refreshFuture` has exactly ONE caller (ProfileResource:87). PlanTemplate
uses ftp for BIKE_PCT_FTP power targets, threshold_pace_run for run, css_swim for swim,
threshold_hr for HR ranges — so a test-applied FTP never reaches upcoming workouts.

Race predictor (RaceReadiness) reads ONLY css_swim, threshold_pace_run and
recentBikeSpeed(activities). FTP is structurally absent — applying an FTP test cannot
move the projection. When there are no >=1h rides in 84 days the bike leg is missing
forever, and the UI tells the user to "set it in Settings → Thresholds", which cannot fix it.

## Todo
- [x] applyResult: run the same future-plan-target refresh the profile PUT runs (after commit)
- [x] TestsView.apply(): no catch — a failed apply is completely silent
- [x] No way to apply a previously-recorded result; `applied` never surfaced in the UI
- [x] Fix the lying "set it in Settings → Thresholds" copy for bike_speed_history
- [x] Zero test coverage on POST /api/tests/result/{id}/apply

## Summary of Changes

**backend-v2/.../tests/FitnessTestsResource.java** — `applyResult` is no longer `@Transactional`; it commits the threshold write in an explicit `QuarkusTransaction.requiringNew()` block, then runs `planTargets.refreshFuture(aid, today)` best-effort and returns `plan_weeks_refreshed`. The method-level `@Transactional` had to go: `refreshFuture` opens its own transaction and would have read the pre-apply athlete row. Same shape as `ProfileResource.updateProfile`.

**backend-v2/.../tests/FitnessTestApplyTest.java** (new) — record → apply over HTTP: 240 W → FTP 228 on the athlete row, recording alone writes nothing, `plan_weeks_refreshed` present, unknown id → 404. Passes locally (2/2) and the full suite is green: 257 tests, 0 failures.

**frontend/src/components/TestsView.tsx** — `apply()` gained a catch (a failed apply was silent); the header card shows the CURRENT profile thresholds so "did that stick?" is answerable in the tab; a new "Recorded — not applied yet" card lists unapplied results with an Apply button (previously the only Apply button lived on ephemeral post-Compute state and vanished on reload/tab switch).

**frontend/src/App.tsx** — passes `athlete.profile` into `TestsView`.

**frontend/src/api.ts** — `TestResult.plan_weeks_refreshed?`.

**frontend/src/components/Dashboards.tsx** — the race-readiness "missing" line no longer tells the user to fix `bike_speed_history` in Settings → Thresholds (impossible); it splits threshold gaps from the ride-history gap.

## Not done — separate decision

The race predictor still ignores FTP entirely (bike leg = recent long-ride avg speed). Follow-up bean covers whether to add an FTP-derived bike-speed fallback.
