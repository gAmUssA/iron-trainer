---
# iron-trainer-waq0
title: 'Zones vertical: /api/athlete/zones in backend-v2 + parity'
status: completed
type: task
priority: normal
created_at: 2026-07-15T22:31:33Z
updated_at: 2026-07-15T22:57:52Z
parent: iron-trainer-eg0j
---

First Phase 4-6 slice: port zones.py (Coggan LTHR bands, %max-HR fallback) to Java, bearer-scoped endpoint, parity test against FastAPI. Pure math — ideal domain-port template.

## Summary of Changes

Shipped PR #39. HrZones ports zones.py (Coggan LTHR bands + %max-HR fallback) with (long) Math.rint for ties-to-even parity with Python round() — Copilot caught the Math.round half-up divergence (16 reachable HR/band combos, regression pinned at threshold_hr=45 Z3 low=40). /api/athlete/zones echoes threshold_hr/max_hr for contract parity (gate caught this on first run). 4 unit + 1 parity test (17/17). First domain-math vertical; template for the rest.
