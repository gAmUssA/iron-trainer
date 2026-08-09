---
# iron-trainer-ex4m
title: Kill PyJson.dumps byte-parity JSON printer
status: todo
type: task
priority: high
created_at: 2026-08-08T23:24:17Z
updated_at: 2026-08-08T23:24:17Z
parent: iron-trainer-y2yz
---

Flagship safe win. util/PyJson.java:24-52 subclasses MinimalPrettyPrinter to force ', ' / ': ' separators reproducing Python json.dumps spacing, so formerly-SHARED DB JSON columns matched FastAPI byte-for-byte. No non-backend-v2 reader byte-compares anymore — every consumer just parses.

## Todo
- [ ] Make PyJson.dumps delegate to the injected ObjectMapper (compact JSON) instead of the custom pretty-printer — one helper change, ~15 call sites unchanged (JobRunner result_json:100, SessionCookie.sign:136, Checkin, Plan, PlannedWorkout, FitnessTestsResource, PlanResource, PlanLlm, StravaMapping…).
- [ ] Confirm SessionCookie.sign is self-consistent (backend-v2 signs+verifies its own; read path base64+JSON-parses, spacing irrelevant) — safe.
- [ ] Verify a round-trip: write→read of result_json / plan / checkin blobs still parses.

## Notes
LOW risk: readers only parse. Do NOT touch PyJson.utcNowIso format here (coupled to string-timestamp columns — see the timestamp child).
