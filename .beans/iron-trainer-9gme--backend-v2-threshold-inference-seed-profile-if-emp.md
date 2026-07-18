---
# iron-trainer-9gme
title: 'backend-v2: threshold inference (seed_profile_if_empty / infer_profile)'
status: in-progress
type: feature
priority: low
created_at: 2026-07-17T01:18:13Z
updated_at: 2026-07-18T13:27:45Z
---

Port analysis.infer_profile + services.seed_profile_if_empty: infer ftp/threshold_hr/threshold_pace_run/css_swim from recent activity history when the profile is empty, called at the end of Strava sync + import. Deferred from [[iron-trainer-wc60]] (the sync works without it; it's a first-connect convenience — no-op once thresholds exist). Unit-test vs test_analysis/infer expectations.

## Summary of Changes

Ported analysis.infer_profile + services.seed_profile_if_empty to backend-v2.

- Analysis.java: inferProfile (84d threshold / 56d availability windows; bike NP→
  ftp*0.95, run pace*1.04, swim CSS, threshold_hr from sustained avg HR else 92%
  of observed max, weekly hours from availability; basis strings) — returns the
  InferredProfile.as_dict() shape (all keys, null where unset). seedProfileIfEmpty
  (fill-blanks-only when no thresholds set, then recomputeAndRebuild).
- InferResource: POST /api/athlete/infer (?apply=1 persists + rebuilds), parity
  with athlete_router.infer.
- Tests: 4 unit tests anchored to a captured FastAPI reference (banker's rounding
  round(256.5)=256.0 / round(0.3125,1)=0.3, out-of-window max_hr, HR fallback
  round(174.8)=175, saveInferred fill-blanks) + a POST /api/athlete/infer parity
  test (seeded_infer fixture).

seedProfileIfEmpty has no caller yet — it's prep the Strava sync (wc60) will use.

94 backend-v2 + 57 parity tests green.
