---
# iron-trainer-n1zt
title: IngestClient + observer/background delivery + foreground catch-up + Sync-now
status: todo
type: task
created_at: 2026-07-21T15:41:54Z
updated_at: 2026-07-21T15:41:54Z
parent: iron-trainer-yrsz
---

IngestClient emits the HAE-shaped payload (bearer auth) to POST /api/health/ingest — zero backend changes. Delivery: HKObserverQuery + enableBackgroundDelivery(.hourly) registered in App init → on fire, run anchored query → POST → advance anchor after confirm → ALWAYS call observer completion (3 misses = iOS disables delivery). Plus foreground catch-up on scenePhase.active + a manual 'Sync now' row. Background delivery doesn't work in Simulator → device test.
