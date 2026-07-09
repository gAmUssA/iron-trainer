---
# iron-trainer-y6ss
title: 'Weekly Check-in: one-tap sync → reconcile → replan with a narrative summary'
status: in-progress
type: feature
priority: normal
created_at: 2026-07-09T18:30:38Z
updated_at: 2026-07-09T18:39:02Z
---

One action composes the adaptive loop that currently takes three manual buttons:
- [x] POST /api/plan/checkin: sync (graceful degrade) → reconcile → replan next week → tests-due (RETEST_DAYS)
- [x] Structured payload + server-built story[] (compliance %, form flag + TSB, hours before→after from workout durations, tests due, top-2 key sessions)
- [x] CheckinCard atop Dashboard (when a plan exists); refreshes all data on completion — visually verified end-to-end incl. a live LLM replan
- [x] Test (157 green) + ADR 0014; PR open, held
- [ ] Follow-up bean: iOS check-in button hitting the same endpoint
