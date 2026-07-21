---
# iron-trainer-ltfs
title: 'HAE overlap → deprecate: verify native parity, then remove Health Auto Export'
status: todo
type: task
priority: normal
created_at: 2026-07-21T15:41:54Z
updated_at: 2026-07-21T21:03:20Z
parent: iron-trainer-yrsz
---

Run native + HAE simultaneously for 2-4 weeks; backend dedup makes double-ingest safe. Verify native daily_recovery rows match HAE (sleep stages, HRV, RHR) before cutting over. Then update Settings/onboarding to drop the HAE setup instructions and tell the user to delete the HAE app + its automation. Final: remove HAE-specific docs.

## Deferred — keep HAE parallel (2026-07-21, Viktor's call)
Native sync verified in prod: 3x POST /api/health/ingest athlete=2 -> 200 at 19:10, all 7 daily_recovery rows written updated_at=19:10:43 (exactly the 7-day syncWindowDays). Data clean (sleep stages/HRV/RHR/resp/VO2 realistic).
NO DUPLICATE RISK running HAE + native in parallel: daily_recovery has UNIQUE(athlete_id, date); ingest upserts by that key (find-or-create then applyFields in place). Both pipelines write the SAME daily row — never a second. Only overlap: shared fields (sleep_h/hrv_ms/rhr_bpm) are last-write-wins and converge (same Apple Health source). Native also touches no other table (no activity dupes).
HOLD ltfs (HAE removal) until Viktor decides native has run long enough as sole source. When resumed: drop the legacy Settings section + HAE onboarding + tell the user to delete the HAE app.
