---
# iron-trainer-adix
title: 'backend-v2: metrics-write vertical (compute_tss + rebuild_metrics) — unblocks apply + Strava sync'
status: completed
type: feature
priority: normal
created_at: 2026-07-16T19:09:34Z
updated_at: 2026-07-16T22:10:31Z
---

Port the metrics WRITE side to Quarkus: metrics.compute_tss (per-activity TSS: power/pace/HR by sport) + metrics.performance_management (PMC/CTL/ATL/TSB build) + rebuild_metrics/store_metrics + recompute_tss. The PMC read vertical only ported the READ side. This write vertical is the shared dependency for: (1) POST /api/tests/result/{id}/apply (apply_test_result cascades save_profile→recompute_tss→rebuild_metrics), and (2) Strava sync. Byte-parity on recomputed activity TSS + metrics_daily rows is the risk. Blocks the fitness-test apply write [[iron-trainer-rskm]] and the Strava vertical. Deferred from the fitness-test writes PR (record+schedule shipped without it).



## Summary
Ported the metrics-write math + cascade to Quarkus:
- Metrics.java: compute_tss (power/pace/hr/duration IF + TSS), performance_management (CTL/ATL/TSB), normalize_sport, daily_tss. 7 unit tests mirror test_metrics.py.
- MetricsWrite.java: recompute_tss (all activities), rebuild_metrics (non-dup → PMC → store), store_metrics (replace metrics_daily), save_profile (threshold write). Activity entity gains tss/intensity_factor/tss_method write cols.
- Consumer: POST /api/tests/result/{id}/apply endpoint (the cascade: save_profile → recompute_tss → rebuild_metrics). End-to-end parity: applied response + rebuilt /pmc byte-identical across backends.
- FastAPI rebuild_metrics pinned to UTC 'today' (matching backend-v2 + readiness-tz) so the metrics_daily series ends on the same day on both.

Also completes the fitness-test apply (from [[iron-trainer-rskm]]); write TRAFFIC flip still gated on proxy-POST + Phase 7 [[iron-trainer-93o6]]. Unblocks [[iron-trainer-3ptl]] (Strava sync reuses this math).
