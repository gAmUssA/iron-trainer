---
# iron-trainer-epb7
title: 'HR zones: calculator, zone-based prescriptions, AI planner integration'
status: in-progress
type: feature
priority: normal
created_at: 2026-07-09T13:47:46Z
updated_at: 2026-07-09T13:52:35Z
---

Viktor: use HR zones in workout planning (we have LTHR/max HR), feed zones to the AI planner, and add an HR-zone calculator.
- [x] zones.py: Coggan LTHR 5-zone model, %max-HR fallback, Z5 capped at max HR, intensity→zone 1:1 mapping
- [x] GET /api/athlete/zones (pure derivation, reports basis)
- [x] Template: HR-range step targets when FTP/run-pace missing (→ Watch HR alerts via existing iOS path); every description carries 'ZN · HR lo–hi bpm'; swim stays pace-only
- [x] LLM prompts: zone table injected + instruction to anchor sessions to Z1–Z5 and name the zone
- [x] ZonesCard on Thresholds tab (basis-aware copy, refetches on threshold change) — visually verified
- [ ] Tests + ADR 0013 + PR (hold for merge)
