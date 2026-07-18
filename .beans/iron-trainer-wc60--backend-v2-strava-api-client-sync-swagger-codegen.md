---
# iron-trainer-wc60
title: 'backend-v2: Strava API client + sync (swagger codegen)'
status: completed
type: feature
priority: normal
created_at: 2026-07-16T23:53:05Z
updated_at: 2026-07-18T18:18:50Z
---

Port strava.py (exchange_code, refresh_access_token, fetch_activities, fetch_activity_detail, deauthorize) using Strava's OpenAPI Java swagger-codegen client (https://developers.strava.com/docs/#client-code). Wire POST /api/strava/sync: valid_access_token (refresh if expired via strava_token_expires_at) → fetch_activities(after) → _map_activity → upsert_activities → deduplicate(fetch_details) → recompute_tss+rebuild_metrics (adix). Also the dedup device-name FETCH path (fetch=true+connected). Test vs a MOCKED Strava (WireMock/@InjectMock), no live calls. Reuses [[iron-trainer-adix]] + PR1 dedup/_map_activity. Split from [[iron-trainer-3ptl]].



## From PR1 review (deferred here)
- dedup fetch=true device-name lookup: PR1's /dedup enforces the 409-not-connected guard but does NOT fetch device names (device_fetched=0); a connected athlete with MISSING stored device names gets different primary selection than FastAPI. Add the fetch_activity_detail device-name resolution + re-cluster here.
- Token-expiry live refresh in the connection check (Python valid_access_token refreshes on expiry) belongs here too.



## Client + sync SHIPPED
- StravaApi: Quarkus REST Client (declarative interface, configKey 'strava') — chose this over swagger-codegen: idiomatic + zero build tooling for 3 endpoints (token/activities/activityDetail). URL overridable for tests.
- StravaTokens: validAccessToken (refresh on expiry via /oauth/token) + saveTokens. Athlete gains strava_access_token/expires_at.
- StravaSync: run_sync — token → paginated fetch (external, no tx) → map+upsert → prune old → dedup → rebuild PMC (tx). POST /api/strava/sync.
- Test: WireMock (org.wiremock) full-sync integration — real HTTP mock of Strava + Dev Services Postgres, asserts token refresh + upsert + map + rebuild.
- Deferred: seed_profile_if_empty/infer_profile [[iron-trainer-9gme]]; dedup device-name fetch enrichment (client method exists, not wired); async envelope [[iron-trainer-s6v3]].

## Inherited from 9gme: wire seed_profile_if_empty

Port services.seed_profile_if_empty and call it at the end of Strava sync +
archive import (mirrors FastAPI services.py:34 / :164), inside @Transactional.
Contract: infer → Analysis.saveInferred (fill-blanks-only, already in backend-v2)
→ recompute_tss ONLY (add a recompute-tss-only method to MetricsWrite — Python
seed does NOT rebuild; the caller rebuilds afterward). Guard: no-op when the
athlete already has ftp/threshold_hr/threshold_pace_run/css_swim. Analysis.inferProfile
is already ported (9gme/PR #63).

## Slice A shipped: seed_profile_if_empty wired into sync (PR pending)

MetricsWrite.recomputeTss(aid) added (recompute_tss only — no rebuild).
Analysis.seedProfileIfEmpty(aid, today) re-added with the recompute-only contract
(infer → saveInferred → recomputeTss; no-op when any threshold set). Wired into
StravaSync.persist between dedup and rebuild (FastAPI run_sync order), and the
result surfaces as profile_seeded / inferred_profile. 2 @QuarkusTests (seeds+re-costs;
no-op). 100 backend-v2 + 57 parity green. Slice A merged (PR #66). REMAINING (slice B): dedup device-name fetch enrichment
(fetchActivityDetail exists, not wired) — next.

## Slice B shipped: dedup device-name fetch enrichment (PR pending)

DedupService.resolveMissingDeviceNames — the EXTERNAL detail-fetch phase, run
OUTSIDE any DB tx (per-activity HTTP must not hold a DB connection): capped,
429→break, other-error→skip, deviceless-success still counts (matches
services.deduplicate). Dedup.clusteredNeedingDevice / applyDeviceNames helpers.
StravaResource.dedup + StravaSync.runSync refactored to 3 phases: tx read+cluster
→ external fetch → tx apply+mark+(seed)+rebuild, via QuarkusTransaction (no
tx-held-across-HTTP). validAccessToken now supplies the /dedup 409+refresh (closes
the deferred token-refresh gap). run_sync response omits device_fetched (parity);
/dedup returns it. Tests: DedupServiceTest (WireMock: fetch/cap/429/500/no-auth),
DedupTest (+2 pure), StravaSyncTest still green under the phased runSync. 107
backend-v2 + 57 parity green. wc60 COMPLETE (slices A+B).
