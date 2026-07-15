---
# iron-trainer-wb53
title: 'Research: custom race types beyond triathlon (run/bike/swim-only events)'
status: completed
type: task
priority: normal
created_at: 2026-07-15T15:49:12Z
updated_at: 2026-07-15T15:54:52Z
---

Research how to generalize Iron Trainer's race model + planner beyond 70.3 triathlon: single-sport events (marathon/half, gran fondo/century, open-water swim), custom distances, multi-race seasons. Agent researching: current race/plan model assumptions, domain patterns (periodization per sport), planner/template/validator changes, readiness/race-readiness projections per event type, UI implications.

## Summary of Changes

Research complete → docs/research/custom-races.md. Key: adaptive loop already event-agnostic; tri-ness lives in ~6 constants (cutoffs_for 70.3 fallback, SPORT_SPLIT/fixed week, validator caps, LLM personas, LEG_DISTANCES/3-leg readiness, nutrition timeline, RaceCard). Design: additive event_type+legs_json + single EVENT_PROFILES dict; P1 run races ~2-3d, P2 ride/swim ~1d, P3 multisport+multi-race season ~3-5d.
