---
# iron-trainer-6elz
title: 'backend-v2: enrich plan-generate LLM prompt fidelity (adjust_season)'
status: todo
type: task
priority: normal
created_at: 2026-07-19T00:42:10Z
updated_at: 2026-07-19T00:42:10Z
---

PR #73 review follow-ups on the LLM adjust_season path (dormant/not parity-tested; validator guarantees safety regardless):
- #2: assembleGen fitness context drops recent_weeks (FastAPI _fitness_summary sends 4-week weekly_volume). Port dashboards.weekly_volume into the LLM context. (Reviewer also cited readiness/checkins/compliance — verify against _fitness_summary; those may belong to the checkin path.)
- #3: adjust_season is passed the full hr_zones {basis,zones} map; FastAPI sends just the zones list labelled with basis. Match the prompt shape.
- #5: convertWeeks always emits target_tss (null when LLM omits); Python uses the raw LLM week dict (key absent). Minor saved-weeks_json divergence on the LLM path.
- #9 (micro-opt): HrZones.hrRangeForIntensity rebuilds the 5-band table 3x/workout in expandWeek; cache per profile.
All LLM-path only; the deterministic template is parity-green. Do when tuning the season LLM (also bump max-tokens for the structured season, see ADR 0033).
