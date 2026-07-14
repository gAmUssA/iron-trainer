---
# iron-trainer-clye
title: Explore Health Auto Export → ingest recovery data (sleep, HRV, RHR)
status: todo
type: task
created_at: 2026-07-14T20:29:05Z
updated_at: 2026-07-14T20:29:05Z
---

Explore the Health Auto Export app (https://www.healthyapps.dev/apps/health-auto-export/) as a zero-API-compliance path for recovery data: it pushes Apple Health metrics (150+ incl. sleep, HRV, heart rate) as JSON/CSV to a REST endpoint on a schedule — no Garmin/Oura/Whoop API needed, data stays user-controlled. Investigate the JSON payload schema (docs + a real sample export), then prototype a POST /api/health/ingest endpoint storing daily sleep/HRV/RHR/readiness inputs. Alternative/complement: read HealthKit directly in the iOS app and upload. Feeds the readiness call (iron-trainer-vhef) with real recovery signals and the feel-vs-data check-in (iron-trainer-p526).
