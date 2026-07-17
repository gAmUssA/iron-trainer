---
# iron-trainer-9gme
title: 'backend-v2: threshold inference (seed_profile_if_empty / infer_profile)'
status: todo
type: feature
priority: low
created_at: 2026-07-17T01:18:13Z
updated_at: 2026-07-17T01:18:13Z
---

Port analysis.infer_profile + services.seed_profile_if_empty: infer ftp/threshold_hr/threshold_pace_run/css_swim from recent activity history when the profile is empty, called at the end of Strava sync + import. Deferred from [[iron-trainer-wc60]] (the sync works without it; it's a first-connect convenience — no-op once thresholds exist). Unit-test vs test_analysis/infer expectations.
