---
# iron-trainer-ltfs
title: 'HAE overlap → deprecate: verify native parity, then remove Health Auto Export'
status: todo
type: task
created_at: 2026-07-21T15:41:54Z
updated_at: 2026-07-21T15:41:54Z
parent: iron-trainer-yrsz
---

Run native + HAE simultaneously for 2-4 weeks; backend dedup makes double-ingest safe. Verify native daily_recovery rows match HAE (sleep stages, HRV, RHR) before cutting over. Then update Settings/onboarding to drop the HAE setup instructions and tell the user to delete the HAE app + its automation. Final: remove HAE-specific docs.
