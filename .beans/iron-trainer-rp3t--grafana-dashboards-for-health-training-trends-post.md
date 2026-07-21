---
# iron-trainer-rp3t
title: Grafana dashboards for health + training trends (Postgres)
status: todo
type: task
priority: low
created_at: 2026-07-21T06:23:34Z
updated_at: 2026-07-21T06:23:34Z
---

The HAE reference server ships 3 Grafana dashboards (Health Metrics: sleep stacked bar, HR candlestick, active energy, steps, cycling distance; Workout Details: geomap route + HR timeseries; Workouts Table). Adopt the IDEAS for an internal ops/health view — but against OUR Postgres (Railway), NOT their MongoDB/Infinity-plugin stack. Use the Grafana Postgres datasource querying daily_recovery + Activity + MetricDaily.

Candidate panels: HRV/RHR/VO2max trend, sleep-stage stacked bar, weight trend, training-load (TSS/CTL/ATL/TSB) timeseries, workout route geomap, recent-workouts table, plan-adherence. Also an ops row (deploy health, request rates) complementing BootUI.

## Todos
- [ ] Stand up Grafana (Railway service or local docker-compose) pointed at prod Postgres (read-only user)
- [ ] Port 2-3 dashboard ideas to Postgres SQL panels (start: recovery trends + training load)
- [ ] Decide hosting (internal-only; do NOT expose athlete data publicly)
- [ ] Commit dashboard JSON under ops/ or docs/
