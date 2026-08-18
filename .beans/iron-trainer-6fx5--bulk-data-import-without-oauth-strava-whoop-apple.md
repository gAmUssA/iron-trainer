---
# iron-trainer-6fx5
title: Bulk data import without OAuth (Strava / WHOOP / Apple Health)
status: todo
type: epic
priority: high
created_at: 2026-08-18T16:15:53Z
updated_at: 2026-08-18T16:16:46Z
parent: iron-trainer-sgfg
blocked_by:
    - iron-trainer-4lve
---

Local mode is only useful if data can get in without OAuth. Two of the three paths
already exist and just need surfacing plus instructions; the third is new.

## Current state (verified 2026-08-18)

| Source | Parser | Endpoint | Status |
|---|---|---|---|
| Strava export ZIP | `StravaArchive.java` | `POST /api/strava/import` | **exists**, wired into ConnectCard |
| WHOOP export ZIP | `WhoopArchive.java` | WhoopView upload | **exists** |
| Apple Health | none | — | **missing** |

Apple Health today arrives only via the Health Auto Export iOS app (Premium, pushes
to `/api/health/ingest`) or the native iOS helper. A self-hoster with no iPhone app
and no HAE subscription has no route in at all — but every iPhone can produce
`export.zip` from Health -> profile -> Export All Health Data.

## Todo
- [ ] **Apple Health `export.xml` importer** (the real work). Streaming parse — these
      exports are routinely 100s of MB to multiple GB, so a DOM parse will OOM the
      container. Map to the same `daily_recovery` columns the HAE ingest already
      fills, and reuse its per-field upsert so a partial import cannot blank fuller
      values
- [ ] Run it as an async job with progress — a multi-GB parse must not be a blocking
      HTTP request. The job framework already exists (`/api/jobs`)
- [ ] Idempotency: re-importing the same export must not double-count. The HAE path
      already learned this the hard way (cumulative fields take a daily MAX, not a
      SUM — see bean mg1n); the archive importer must follow the same rule
- [ ] Surface all three importers in one "Import your data" screen in local mode
      instead of scattered across Settings / WHOOP / Connect
- [ ] Write the how-to-get-your-export instructions for each of the three (Strava:
      Settings -> My Account -> Download; WHOOP: member export; Apple: Health app ->
      profile -> Export All Health Data). Non-technical users will not find these
- [ ] Large-upload handling: request size limits, disk temp space, and a clear error
      when a 2 GB file is rejected rather than a silent 413
