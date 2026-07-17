---
# iron-trainer-wc60
title: 'backend-v2: Strava API client + sync (swagger codegen)'
status: todo
type: feature
priority: normal
created_at: 2026-07-16T23:53:05Z
updated_at: 2026-07-16T23:53:05Z
---

Port strava.py (exchange_code, refresh_access_token, fetch_activities, fetch_activity_detail, deauthorize) using Strava's OpenAPI Java swagger-codegen client (https://developers.strava.com/docs/#client-code). Wire POST /api/strava/sync: valid_access_token (refresh if expired via strava_token_expires_at) → fetch_activities(after) → _map_activity → upsert_activities → deduplicate(fetch_details) → recompute_tss+rebuild_metrics (adix). Also the dedup device-name FETCH path (fetch=true+connected). Test vs a MOCKED Strava (WireMock/@InjectMock), no live calls. Reuses [[iron-trainer-adix]] + PR1 dedup/_map_activity. Split from [[iron-trainer-3ptl]].
