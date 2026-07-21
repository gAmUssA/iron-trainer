---
# iron-trainer-rp3t
title: Custom in-app dashboards replicating Grafana health/training views
status: todo
type: task
priority: normal
created_at: 2026-07-21T06:23:34Z
updated_at: 2026-07-21T06:32:26Z
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
