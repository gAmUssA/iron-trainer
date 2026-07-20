---
# iron-trainer-0jwt
title: 'backend-v2: port health vertical (recovery read + Health-Auto-Export ingest)'
status: completed
type: feature
priority: high
created_at: 2026-07-20T05:29:24Z
updated_at: 2026-07-20T05:41:32Z
---

Port GET /api/health/recovery + POST /api/health/ingest to backend-v2. Expand the DailyRecovery entity to all daily_recovery columns; port health_ingest.parse_payload (Health Auto Export payload parser) + upsert_daily_recovery. Part of the Phase-7 tail (bean p4co follow-up).

## Shipped
DailyRecovery entity expanded to all 14 columns; HealthIngest.parsePayload (offset dates, lb→kg/°F→°C, same-day averaging, sleep resolution); GET /api/health/recovery + POST /api/health/ingest (malformed→{ok:false}; auth only when storing). Tests: HealthIngestTest + HealthEndpointTest + ingest/recovery parity (byte-identical vs real backends). v2 178 green. ADR 0041. Phase-7 backlog: 9 → 7.
