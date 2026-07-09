---
# iron-trainer-y6ss
title: 'Weekly Check-in: one-tap sync → reconcile → replan with a narrative summary'
status: completed
type: feature
priority: normal
created_at: 2026-07-09T18:30:38Z
updated_at: 2026-07-09T23:21:17Z
---

One action composes the adaptive loop that currently takes three manual buttons:
- [x] POST /api/plan/checkin: sync (graceful degrade) → reconcile → replan next week → tests-due (RETEST_DAYS)
- [x] Structured payload + server-built story[] (compliance %, form flag + TSB, hours before→after from workout durations, tests due, top-2 key sessions)
- [x] CheckinCard atop Dashboard (when a plan exists); refreshes all data on completion — visually verified end-to-end incl. a live LLM replan
- [x] Test (157 green) + ADR 0014; PR open, held
- [x] Follow-up bean filed: iron-trainer-6x3a (iOS check-in button)

## Summary of Changes

POST /api/plan/checkin composes sync → reconcile → replan → test-due → key sessions with a server-built story[]; Dashboard Check-in card. PR #19 merged. ADR 0014.
