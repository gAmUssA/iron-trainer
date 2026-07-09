---
# iron-trainer-d2ik
title: 'Widget staleness: consider BGAppRefresh'
status: scrapped
type: feature
priority: deferred
created_at: 2026-07-08T17:29:56Z
updated_at: 2026-07-09T14:43:14Z
---

Today-workout widget degrades to 'Open app to sync' after 7 idle days (by design — no background sync, ADR 0012). If it annoys in practice, a BGAppRefresh task could rewrite the snapshot; needs its own ADR.

## Reasons for Scrapping

Not a task — a design posture already decided and recorded in ADR 0012: no background sync; the workout widget degrades honestly after 7 idle days and the countdown widget is immune. Re-open only if staleness actually bites in practice (that decision belongs to a fresh bean + ADR, not this placeholder).
