---
# iron-trainer-p4co
title: 'backend-v2: port the FastAPI tail (profile, health/recovery, status, me/logout, export-zip)'
status: in-progress
type: feature
priority: high
created_at: 2026-07-20T04:41:43Z
updated_at: 2026-07-20T15:47:52Z
---

Bundle the non-token-minting FastAPI-only endpoints to backend-v2 so Phase 7 can proceed. Excludes device-pairing token minting (separate security slice). Endpoints: GET /api/athlete, PUT /api/athlete/profile, GET /api/health, POST /api/health/ingest, GET /api/health/recovery, GET /api/status, GET /api/me, POST /api/auth/logout, GET /api/export/plan.zip, GET /api/export/week/{week_start}.zip.

## Shipped (part 1 — app tail reads)
GET /api/health(+deep), GET /api/status, GET /api/me, POST /api/auth/logout, GET /api/athlete (+gi_tolerance entity field). v2 173 green; 5 parity tests byte-identical vs real backends. ADR 0040.

## Remaining (follow-up slices)
- PUT /api/athlete/profile (recompute TSS + refresh future plan targets)
- GET /api/health/recovery + POST /api/health/ingest (daily_recovery entity + Health-Auto-Export parser)
- export ZIP: plan.zip + week/{week_start}.zip
- device pairing (bearer-token minting — security slice)

## Code-review fixes
parseDate leniency (colon/non-colon offset, naive, bare date); truthy date/startDate fallback; num() boolean coercion; ?days via Params.intParam (422 parity); ingest 401 swallowed per-day (FastAPI parity). v2 179 green; parity re-verified.
