---
# iron-trainer-3ptl
title: 'backend-v2: port Strava sync vertical'
status: in-progress
type: feature
priority: normal
created_at: 2026-07-16T21:59:25Z
updated_at: 2026-07-17T00:02:07Z
---

Port the Strava sync flow to Quarkus (after the metrics-write vertical [[iron-trainer-adix]], which it reuses for recompute_tss + rebuild_metrics). Sync pulls activities from the Strava API, normalizes/dedupes, computes TSS, rebuilds the PMC. 

Client tooling: Strava publishes an official OpenAPI/Swagger spec with Java client codegen — https://developers.strava.com/docs/#client-code (swagger-codegen example). Use that generated Java client for the Strava API calls instead of hand-rolling, matching the FastAPI app's endpoints/scopes. Requires OAuth token handling (the auth vertical / STRAVA_CLIENT_* env). Blocked by adix (TSS/PMC math) + likely Phase 7 auth for token storage.

Reference from user 2026-07-16.



## Scoped into phases (user: deterministic core first)
- PR1 (this): dedup (cluster_duplicates + primary_of) + POST /api/strava/dedup?fetch=false (cluster→mark→rebuild_metrics via adix) + normalized_power + _map_activity as pure/unit-tested. Parity-verified on seeded duplicate activities. Device-name FETCH (fetch=true, connected) deferred with the API client.
- PR2 (deferred [[iron-trainer-svapi]]): Strava API client via swagger codegen (exchange_code/refresh/fetch_activities/detail) + POST /sync orchestration + dedup device-fetch. Tested vs MOCKED Strava.
- PR3 (deferred [[iron-trainer-svoauth]]): OAuth GET /connect + /callback — needs Phase 7 session auth + STRAVA_CLIENT_SECRET.
- PR4 (deferred [[iron-trainer-svarchive]]): GDPR archive import (POST /import + strava_import.parse_archive).



## PR1 SHIPPED: dedup core
Dedup.java (cluster_duplicates + primary_of, sport-aware source preference), normalized_power, _map_activity — all pure/unit-tested (mirror test_dedup.py + test_metrics NP). POST /api/strava/dedup (fetch=false: cluster→mark→rebuild_metrics via adix; fetch=true: 409 when not connected). Byte-parity verified (dedup stats + rebuilt /pmc). Remaining phases: [[iron-trainer-wc60]] (API client+sync), [[iron-trainer-xtre]] (OAuth), [[iron-trainer-f6ui]] (archive).
