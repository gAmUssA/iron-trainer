---
# iron-trainer-wjjc
title: 'Async Strava sync fails: ContextNotActiveException'
status: completed
type: bug
priority: high
created_at: 2026-08-08T21:55:03Z
updated_at: 2026-08-08T22:20:37Z
---

Every incremental async sync job (kind 'sync', ?async=1) fails with jakarta.enterprise.context.ContextNotActiveException. Root cause: StravaSync.runSync's setup reads latestActivityEpoch(aid) — Activity.find(...) — outside any transaction. On the request path a request context masked it; on the JobRunner virtual thread there is neither request context nor tx. validAccessToken/persistDeviceNames are @Transactional; this one read was not. Fix: wrap the read in QuarkusTransaction.requiringNew(). Regression: StravaSyncAsyncTest runs the read on a virtual thread.

## Summary of Changes
- StravaSync.latestActivityEpoch: read wrapped in QuarkusTransaction.requiringNew(); made package-private.
- New StravaSyncAsyncTest reproduces the async (no-request-context) path.
Observed failing job ids (athlete 2): 18,19,21,22,24,25. Older athlete-4 failures were 401 (expired Strava token), unrelated.
