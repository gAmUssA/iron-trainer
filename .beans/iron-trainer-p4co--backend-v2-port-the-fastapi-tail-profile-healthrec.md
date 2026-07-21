---
# iron-trainer-p4co
title: 'backend-v2: port the FastAPI tail (profile, health/recovery, status, me/logout, export-zip)'
status: completed
type: feature
priority: high
created_at: 2026-07-20T04:41:43Z
updated_at: 2026-07-21T06:13:06Z
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

## Code-review fixes
numeric-string + bool→float coercion (pydantic lax); empty/non-object body → 422; refreshFuture aborts on bad week_start; applyFueling de-duplicated. v2 183 green; parity re-verified.

## Code-review fixes
duplicate entry-name dedup (200 not 500); compact-ISO week_start parse (404 not 500); filename null→None; lazy README load. v2 186 green; parity re-verified.

## Code-review fixes
pessimistic-lock claim (race); device_name/name non-string→422; validate-before-throttle; non-object body→422; XFF isEmpty; SecureTokens util (StravaOAuth reuses). v2 191 green; parity re-verified.

## Summary of Changes 2026-07-21
Ported the FastAPI tail (profile, health/recovery, status, /me) to backend-v2 — PR #80. All endpoints live on the Quarkus front door.
