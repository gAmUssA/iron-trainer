---
# iron-trainer-ory3
title: 'backend-v2: port /metrics/trends (sport_trends + insights.build)'
status: todo
type: feature
created_at: 2026-07-17T22:58:32Z
updated_at: 2026-07-17T22:58:32Z
parent: iron-trainer-eom4
---

The last analytics read: GET /api/metrics/trends. Port dashboards.sport_trends + the full insights.build (~500 lines: per-sport progression points, rolling trendlines, improving/declining verdicts, weekly intensity mix, PRs, CTL race-day trajectory, data freshness). days windows only the chart points; insights/verdicts always use the full record. Math-heavy — needs careful banker's-rounding parity. Its own PR.
