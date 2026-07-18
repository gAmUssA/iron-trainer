---
# iron-trainer-ory3
title: 'backend-v2: port /metrics/trends (sport_trends + insights.build)'
status: completed
type: feature
priority: normal
created_at: 2026-07-17T22:58:32Z
updated_at: 2026-07-18T00:51:54Z
parent: iron-trainer-eom4
---

The last analytics read: GET /api/metrics/trends. Port dashboards.sport_trends + the full insights.build (~500 lines: per-sport progression points, rolling trendlines, improving/declining verdicts, weekly intensity mix, PRs, CTL race-day trajectory, data freshness). days windows only the chart points; insights/verdicts always use the full record. Math-heavy — needs careful banker's-rounding parity. Its own PR.

## Summary of Changes
Ported /api/metrics/trends: Dashboards.sportTrends + full Insights engine (rolling_mean 28d sliding window, slope_pct 84d least-squares, verdicts, intensity_mix 12wk IF buckets, personal_records, ctl_trajectory race-day projection, freshness). 3 parity tests, full trends bundle byte-identical, gate 54/54. ADR 0026. Completes all analytics_router reads.
