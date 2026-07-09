---
# iron-trainer-qmje
title: Plan staleness hint when weekly hours change
status: in-progress
type: feature
priority: normal
created_at: 2026-07-09T02:51:51Z
updated_at: 2026-07-09T02:53:30Z
---

Validated: weekly_hours_target only feeds generate_plan(); saving thresholds never touches the existing plan, the UI claims 'Saved — recomputed', and replan_week deliberately preserves stored week volumes. Option A chosen: surface the mismatch.
- [x] plan.base_weekly_hours column (migration d5a7b9c1e3f5) recorded at generation (template + LLM paths)
- [x] GET /api/plan exposes it (via _plan_dict); banner on Plan + Thresholds tabs with regenerate guidance incl. the history-loss caveat
- [x] Backend test added (146 total pass)
- [ ] PR (hold for merge)
