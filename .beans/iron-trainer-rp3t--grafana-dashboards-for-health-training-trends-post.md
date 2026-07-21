---
# iron-trainer-rp3t
title: Custom in-app dashboards replicating Grafana health/training views
status: completed
type: task
priority: normal
created_at: 2026-07-21T06:23:34Z
updated_at: 2026-07-21T13:40:52Z
---

The HAE reference server ships 3 Grafana dashboards (Health Metrics: sleep stacked bar, HR candlestick, active energy, steps, cycling distance; Workout Details: geomap route + HR timeseries; Workouts Table). Adopt the IDEAS for an internal ops/health view — but against OUR Postgres (Railway), NOT their MongoDB/Infinity-plugin stack. Use the Grafana Postgres datasource querying daily_recovery + Activity + MetricDaily.

Candidate panels: HRV/RHR/VO2max trend, sleep-stage stacked bar, weight trend, training-load (TSS/CTL/ATL/TSB) timeseries, workout route geomap, recent-workouts table, plan-adherence. Also an ops row (deploy health, request rates) complementing BootUI.

## Todos
- [ ] Stand up Grafana (Railway service or local docker-compose) pointed at prod Postgres (read-only user)
- [ ] Port 2-3 dashboard ideas to Postgres SQL panels (start: recovery trends + training load)
- [ ] Decide hosting (internal-only; do NOT expose athlete data publicly)
- [ ] Commit dashboard JSON under ops/ or docs/

## Re-scope 2026-07-21: custom dashboards, NOT Grafana
Viktor: not using Grafana — build **custom in-app dashboards** (React, in the existing frontend) that replicate the Grafana panel ideas, fed by our backend-v2 API (no Grafana, no second datastore, no Infinity plugin).
- Panels to replicate: HRV/RHR/VO2max trend, sleep-stage stacked bar, weight trend, training load (TSS/CTL/ATL/TSB), workout route map, recent-workouts table.
- Data via backend-v2 endpoints over daily_recovery / Activity / MetricDaily (add read endpoints as needed).
- Use the app's charting approach; see the dataviz skill for chart design.
- Lives in the app UI (athlete-facing trends), not a separate ops tool.

## Concretized 2026-07-21 — ADR 0047 (extends existing Fitness tab infra)
NOT greenfield: recharts is installed + heavily used; a Fitness tab already has PMC/sport-trends/PRs. rp3t = a NEW **Recovery** tab (Fitness=load, Recovery=readiness). Reuse chartTheme.ts (useChart/COLORS), card layout, RangePicker, api.recovery()/readinessToday().
Panels (recharts, from /api/health/recovery): (1) HRV+RHR dual-axis line + 7d rolling; (2) sleep-stage stacked bar (deep/rem/core/awake); (3) weight trend; (4) activity load (steps+active_energy+exercise_min, the ADR-0046 metrics); (5) VO2max/SpO2/respiratory secondary lines; (6) readiness strip (v2). No route-map panel (Activity has no GPS cols). No backend changes for v1.
Impl: add tab to App.tsx TABS + RecoveryTrendsView.tsx.


## Web impl 2026-07-21 — RecoveryTrendsView
Added frontend/src/components/RecoveryTrendsView.tsx (self-fetching, seq-guarded, RangePicker) + a Recovery tab in App.tsx after Fitness. Panels: HRV+RHR dual-axis w/ 7d rolling, sleep-stage stacked bar (deep/core/rem/awake, last 21), body-weight area+rolling, daily load (steps bar + exercise/energy lines), vitals row (VO2max/SpO2/respiratory, shown only when present). Extended RecoveryDay type in api.ts with the ADR-0046 fields. npm run build (tsc -b && vite build) passes.

## Summary of Changes 2026-07-21
Web Recovery tab shipped (PR #91, ADR 0047) — new tab beside Fitness with HRV+RHR (date-aware 7d rolling), sleep-stage stacked bar, weight, daily load (steps+exercise), and VO2max/SpO2/respiratory sparklines (reused MiniSpark). Built by a parallel agent; 7 code-review findings fixed (sleep-stage math, dual-axis crush, loading state, date-aware rolling, dup/BarChart cleanups, tour). Merged + deployed; LIVE at iron-trainer.up.railway.app (bundle index-BPXsQeOE.js).
