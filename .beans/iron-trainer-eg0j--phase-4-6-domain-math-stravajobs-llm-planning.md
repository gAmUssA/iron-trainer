---
# iron-trainer-eg0j
title: 'Phase 4-6: domain math, Strava+jobs, LLM planning'
status: completed
type: epic
priority: normal
created_at: 2026-07-15T18:21:04Z
updated_at: 2026-07-15T22:57:24Z
parent: iron-trainer-37md
---

Port zones/nutrition/readiness/metrics/fitness-tests with their unit tests (the long pole); Strava OAuth + sync + dedup/reconcile + virtual-thread jobs; planning/LLM via quarkus-langchain4j-anthropic with RESPONSE_FORMAT_JSON_SCHEMA capability (MANDATORY flag — silent prompt fallback without it, wire-proven) + validator port.

## Summary of Changes

Shipped PR #39. HrZones ports zones.py (Coggan LTHR bands + %max-HR fallback) with (long) Math.rint for ties-to-even parity with Python round() — Copilot caught the Math.round half-up divergence (16 reachable HR/band combos). /api/athlete/zones echoes threshold_hr/max_hr for contract parity (parity gate caught this on first run). 4 unit tests + zones parity test (17/17). First domain-math vertical done; template for the rest.
