---
# iron-trainer-p526
title: 'Weekly check-in: feel-vs-data reconciliation + compounding history'
status: completed
type: feature
priority: high
created_at: 2026-07-14T20:11:12Z
updated_at: 2026-07-15T02:38:32Z
parent: iron-trainer-udbc
---

As an athlete I want the weekly check-in to ask how the week FELT (energy, sleep, soreness, stress) and reconcile that with my data, calling out disagreements — and I want past check-ins remembered so trends compound across weeks.

## Todos

- [x] Optional subjective inputs on check-in (energy/sleep/soreness/stress, 1-5 + free text)
- [x] Persist check-in stories (+ inputs) in DB
- [x] Feed last few check-ins + subjective inputs into check-in LLM prompt; reconcile feel vs data, call out disagreements
- [x] Web + iOS input UI (skippable in one tap)
- [x] Tests + ADR

## Summary of Changes

Checkin table (migration f7c9e1a3b5d7) persists date/inputs/story/readiness per run. Subjective inputs energy/sleep/body/stress 1-5 (higher better) + note, sanitized server-side, accepted as optional POST body on /api/plan/checkin (incl. async job path). _feel_vs_data_line() reconciles feel vs readiness (rough-but-green → sleep/stress hint; great-but-amber → overreach warning). _fitness_summary() feeds recent_checkins + todays_feel into the replan LLM prompt. Web CheckinCard feel form (skippable); iOS CheckinFeelSheet + weekly Monday-8:00 reminder toggle (local notification). 5 tests; ADR 0018. Verified live on demo backend + simulator end-to-end.

## TestFlight

Build 0.1.0 (202607142236) uploaded 2026-07-14 — feel sheet, check-in reminder + morning brief toggles all in this beta (also covers xijr).
