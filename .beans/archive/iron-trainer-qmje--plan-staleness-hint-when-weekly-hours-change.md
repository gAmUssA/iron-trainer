---
# iron-trainer-qmje
title: Plan staleness hint when weekly hours change
status: completed
type: feature
priority: normal
created_at: 2026-07-09T02:51:51Z
updated_at: 2026-07-09T03:21:09Z
---

Validated: weekly_hours_target only feeds generate_plan(); saving thresholds never touches the existing plan, the UI claims 'Saved — recomputed', and replan_week deliberately preserves stored week volumes. Option A chosen: surface the mismatch.
- [x] plan.base_weekly_hours column (migration d5a7b9c1e3f5) recorded at generation (template + LLM paths)
- [x] GET /api/plan exposes it (via _plan_dict); banner on Plan + Thresholds tabs with regenerate guidance incl. the history-loss caveat
- [x] Backend test added (146 total pass)
- [x] PR #17 merged + deployed

Extended (Viktor): threshold changes now auto-refresh FUTURE workout targets — refresh_future_plan_targets() re-expands strictly-future weeks from current thresholds (volumes/phases preserved, current week + history untouched, fueling re-costed); hooked into PUT /api/athlete/profile when a prescription-driving field actually changes; save note reports 'upcoming workouts re-targeted (N weeks)'. 2 tests added.

## Summary of Changes

plan.base_weekly_hours staleness banner (hours changes stay explicit — regeneration replaces history) + automatic future-week target refresh when prescription-driving thresholds change (best-effort, current week/history untouched). The fitness-test → thresholds → plan loop now closes automatically.
