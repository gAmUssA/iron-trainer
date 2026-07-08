---
# iron-trainer-d2ik
title: 'Widget staleness: consider BGAppRefresh'
status: draft
type: feature
priority: deferred
created_at: 2026-07-08T17:29:56Z
updated_at: 2026-07-08T17:29:56Z
---

Today-workout widget degrades to 'Open app to sync' after 7 idle days (by design — no background sync, ADR 0012). If it annoys in practice, a BGAppRefresh task could rewrite the snapshot; needs its own ADR.
