---
# iron-trainer-vhef
title: 'Daily readiness call: ACWR-based go-hard/go-easy/rest'
status: todo
type: feature
priority: high
created_at: 2026-07-14T20:10:49Z
updated_at: 2026-07-14T20:11:24Z
---

As an athlete I want a daily readiness call (go hard / go easy / rest) computed from my own training history, so I know whether to push or back off today.

## Todos

- [ ] zones/load: compute daily training load per activity (duration x intensity; use HR-zone weighting where HR present, else duration+sport heuristic)
- [ ] ACWR: 7-day acute vs 28-day chronic from athlete's own history; thresholds ~0.8-1.3 steady, >1.3-1.5 elevated risk
- [ ] Readiness call endpoint: go hard / go easy / rest + one-line reason with actual numbers
- [ ] Signal-not-noise: quiet/green when normal; amber/red flags only when warranted
- [ ] Surface in web Setup/Today area + weekly check-in story
- [ ] iOS Today view row + (optional) widget snapshot field
- [ ] Feed readiness into planner prompt context
- [ ] Tests + ADR
