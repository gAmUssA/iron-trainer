---
# iron-trainer-ids6
title: WHOOP integration — overlay recovery without duplicating Apple Health
status: todo
type: epic
priority: normal
created_at: 2026-07-29T20:37:39Z
updated_at: 2026-07-29T20:37:39Z
---

Goal: bring WHOOP data into Iron Trainer to OVERLAY/COMPARE with our Apple-Health-derived readiness — WITHOUT double-counting data we already ingest via HealthKit.

## Research (2026-07-29, Tavily)
**API:** WHOOP API v2 (v1 deprecated). REST+JSON, base https://api.prod.whoop.com. Pull resources: Recovery (recovery_score %, hrv_rmssd_milli, resting_heart_rate, spo2, skin_temp — delivered via Cycle endpoints), Cycle (day strain, avg/max HR, energy), Sleep (stages, performance/efficiency %, respiratory rate), Workout (sport, strain, HR zones), Profile, Body measurement.

**Access:** SELF-SERVE — sign in at id.whoop.com, create an app, get Client ID/Secret INSTANTLY, free, up to 5 apps. BUT a hard **10-user cap** until the app is APPROVED. Approval = submit app (name, contacts, Privacy Policy URL, brand-guideline adherence, designs); reviews are slow (community reports 60+ day waits, mid-2026). Contrast w/ Garmin: WHOOP is easy to START (instant keys, ≤10 users), but public launch hits a slow approval wall.

**Auth:** OAuth 2.0 Authorization Code. authorize=/oauth/oauth2/auth, token=/oauth/oauth2/token. Scopes: read:recovery, read:sleep, read:workout, read:cycles, read:profile, read:body_measurement, + offline (for refresh). Access token ~1h; refresh tokens ROTATE/single-use (store the latest).

**Delivery:** REST pull (cursor pagination via nextToken; start/end filters). Rate limits: 100 req/min + 10,000 req/day (429 over). Webhooks: recovery/sleep/workout .updated/.deleted, HMAC-SHA256 signed (X-WHOOP-Signature), NOTIFICATION-ONLY → then fetch the record.

## ⚠ DE-DUPLICATION vs Apple Health (the core concern)
**WHOOP WRITES raw metrics INTO Apple Health** (since Mar 2022): HRV (as SDNN), resting HR, respiratory rate, sleep stages, SpO2, skin/wrist temp, workouts. So our EXISTING HealthKit pipeline ALREADY ingests WHOOP-originated raw samples for any user who has WHOOP→Apple Health sync on. **Pulling those same raw metrics from the WHOOP API = double-counting.**

**What is NOT in Apple Health:** WHOOP's proprietary **Recovery %** and **Strain** — computed scores with no HealthKit data type. Only reachable via the WHOOP API. THIS is what justifies a direct API.

**Provenance:** distinguish WHOOP-sourced HealthKit samples via HKSample.sourceRevision.source (HKSource) .name (=="WHOOP") + .bundleIdentifier. Read the bundle id AT RUNTIME (don't hardcode). Note: HealthKit HRV = SDNN vs WHOOP API HRV = RMSSD — NOT interchangeable, never merge blindly.

## RECOMMENDATION (verdict: BOTH, scoped)
- HealthKit (source-tagged) stays the RAW-metric backbone (HRV/RHR/sleep) → feeds our existing 'Iron Trainer Readiness'.
- WHOOP API used ONLY for the proprietary **Recovery %** (+ Strain) → stored as its OWN metric, source='whoop_api'.
- Present two INDEPENDENT, labeled recovery signals side by side (overlay/compare). Zero double-count: only the score with no HealthKit equivalent is pulled directly.
- Caveat: if a WHOOP user has HealthKit sync OFF, our Readiness is blind for them — decide whether to require HealthKit sync or also pull raw from WHOOP for that case.

## Effort
~3-5 focused dev days for OAuth + pull Recovery/Strain + store-as-distinct-source (+ basic webhooks; polling is fine to start). Non-eng long pole = WHOOP app approval (weeks-months) if >10 users.

## Sources
developer.whoop.com/api, /docs/developing/{overview,getting-started,app-approval,oauth,rate-limiting,webhooks}, /docs/developing/user-data/{recovery,sleep}, /v1-v2-migration; developer.apple.com/documentation/healthkit/hksource; WHOOP HealthKit-write since Mar 2022.
