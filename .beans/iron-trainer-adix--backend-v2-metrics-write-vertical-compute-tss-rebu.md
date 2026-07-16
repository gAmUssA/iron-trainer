---
# iron-trainer-adix
title: 'backend-v2: metrics-write vertical (compute_tss + rebuild_metrics) — unblocks apply + Strava sync'
status: todo
type: feature
priority: normal
created_at: 2026-07-16T19:09:34Z
updated_at: 2026-07-16T19:09:34Z
---

Port the metrics WRITE side to Quarkus: metrics.compute_tss (per-activity TSS: power/pace/HR by sport) + metrics.performance_management (PMC/CTL/ATL/TSB build) + rebuild_metrics/store_metrics + recompute_tss. The PMC read vertical only ported the READ side. This write vertical is the shared dependency for: (1) POST /api/tests/result/{id}/apply (apply_test_result cascades save_profile→recompute_tss→rebuild_metrics), and (2) Strava sync. Byte-parity on recomputed activity TSS + metrics_daily rows is the risk. Blocks the fitness-test apply write [[iron-trainer-rskm]] and the Strava vertical. Deferred from the fitness-test writes PR (record+schedule shipped without it).
