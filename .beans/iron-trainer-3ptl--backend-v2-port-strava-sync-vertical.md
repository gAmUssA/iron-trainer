---
# iron-trainer-3ptl
title: 'backend-v2: port Strava sync vertical'
status: todo
type: feature
priority: normal
created_at: 2026-07-16T21:59:25Z
updated_at: 2026-07-16T21:59:25Z
---

Port the Strava sync flow to Quarkus (after the metrics-write vertical [[iron-trainer-adix]], which it reuses for recompute_tss + rebuild_metrics). Sync pulls activities from the Strava API, normalizes/dedupes, computes TSS, rebuilds the PMC. 

Client tooling: Strava publishes an official OpenAPI/Swagger spec with Java client codegen — https://developers.strava.com/docs/#client-code (swagger-codegen example). Use that generated Java client for the Strava API calls instead of hand-rolling, matching the FastAPI app's endpoints/scopes. Requires OAuth token handling (the auth vertical / STRAVA_CLIENT_* env). Blocked by adix (TSS/PMC math) + likely Phase 7 auth for token storage.

Reference from user 2026-07-16.
