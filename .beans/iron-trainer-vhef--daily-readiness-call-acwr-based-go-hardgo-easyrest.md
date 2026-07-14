---
# iron-trainer-vhef
title: 'Daily readiness call: ACWR-based go-hard/go-easy/rest'
status: completed
type: feature
priority: high
created_at: 2026-07-14T20:10:49Z
updated_at: 2026-07-14T21:15:21Z
---

As an athlete I want a daily readiness call (go hard / go easy / rest) computed from my own training history, so I know whether to push or back off today.

## Todos

- [x] Daily load already existed (TSS per activity + MetricDaily series) — reused, no new computation needed
- [x] ACWR: 7-day acute vs 28-day chronic from athlete's own history; thresholds ~0.8-1.3 steady, >1.3-1.5 elevated risk (readiness.py)
- [x] Readiness call endpoint GET /api/metrics/readiness/today: go hard / go easy / rest + one-line reason with actual numbers
- [x] Signal-not-noise: quiet/green when normal; amber/red flags only when warranted; insufficient-data honesty + stale-TSB guard
- [x] Surfaced: web dashboard TodayCall banner + weekly check-in story line and readiness payload
- [x] iOS Today view ReadinessBanner (widget snapshot field deferred — follow-up with morning-brief bean iron-trainer-xijr)
- [x] Feed readiness into planner prompt context (_fitness_summary.readiness_today + week-generation prompt rule)
- [x] Tests (13) + ADR 0016

## Summary of Changes

Shipped ACWR-based daily readiness call. New pure module backend/app/readiness.py (7d/28d rolling ACWR ending yesterday, TSB and hard-day-streak modifiers, staleness guard, insufficient-data honesty). GET /api/metrics/readiness/today; weekly check-in narrates the call and exposes structured readiness; planner LLM context carries readiness_today with a hard rule against scheduling key sessions on rest/easy days. Web TodayCall banner + iOS Today ReadinessBanner (signal-not-noise styling). Verified: 13 tests, live demo backend, Playwright web check, simulator end-to-end via deep-link pairing. ADR 0016.
