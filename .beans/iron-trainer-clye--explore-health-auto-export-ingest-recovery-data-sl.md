---
# iron-trainer-clye
title: Explore Health Auto Export → ingest recovery data (sleep, HRV, RHR)
status: completed
type: task
priority: normal
created_at: 2026-07-14T20:29:05Z
updated_at: 2026-07-15T03:11:07Z
parent: iron-trainer-udbc
---

Explore the Health Auto Export app (https://www.healthyapps.dev/apps/health-auto-export/) as a zero-API-compliance path for recovery data: it pushes Apple Health metrics (150+ incl. sleep, HRV, heart rate) as JSON/CSV to a REST endpoint on a schedule — no Garmin/Oura/Whoop API needed, data stays user-controlled. Investigate the JSON payload schema (docs + a real sample export), then prototype a POST /api/health/ingest endpoint storing daily sleep/HRV/RHR/readiness inputs. Alternative/complement: read HealthKit directly in the iOS app and upload. Feeds the readiness call (iron-trainer-vhef) with real recovery signals and the feel-vs-data check-in (iron-trainer-p526).

## Research complete (2026-07-14)

Full report: docs/research/health-auto-export-rest-api.md. Key findings: payload is {data:{metrics:[{name,units,data:[{qty,date,...}]}]}}; sleep_analysis (Summarize ON) gives per-night core/deep/rem/awake/totalSleep hours; identifiers heart_rate_variability (ms) + resting_heart_rate (bpm); custom Authorization: Bearer header officially supported (our auth); dates are NON-ISO 'yyyy-MM-dd HH:mm:ss Z' with 12h/U+202F locale variants; overlapping windows re-sent → upsert on (athlete_id, local_date); delivery timing unguaranteed (iOS) → eventually-consistent consumers; REST automations are app Premium tier.

## Implementation todos

- [x] daily_recovery table + migration a1b3c5d7e9f1 (wide row, upsert last-write-wins)
- [x] POST /api/health/ingest (bearer device-token auth, lenient parsing, fast 200) + GET /api/health/recovery
- [x] Date parser with %z + 12-hour/U+202F fallback; unit normalization (lb→kg, degF→degC)
- [x] readiness.compute(): HRV/RHR-vs-baseline + short-sleep modifiers (downgrade-only, stale-blind)
- [x] Feed sleep/HRV into check-in story + LLM context (via readiness reasons plumbing)
- [x] Settings: mint/display ingest token + setup guide (deferred to follow-up — device token works today)
- [x] Tests incl. real fixture payloads (6 new; suite 192)

Shipped in PR #27. Observability round added post-review: malformed-JSON + zero-days-stored warnings (metric names only, never values), parsed{} debug block in responses.
