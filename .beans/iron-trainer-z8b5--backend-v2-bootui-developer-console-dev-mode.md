---
# iron-trainer-z8b5
title: 'backend-v2: BootUI developer console (dev mode)'
status: scrapped
type: task
priority: low
created_at: 2026-07-20T19:36:45Z
updated_at: 2026-07-21T06:13:06Z
---

Added bootui-quarkus 1.12.0 (dev/test-only, Quarkus 3.37.x) for a dev console at http://localhost:8080/bootui. Requested by Viktor.

## Summary of Changes
PR #86 merged; all CI incl native green (confirms prod build unaffected). Dev/test-only, dark in production — verified: /bootui -> 200 in local quarkus:dev; on the live prod front door /bootui -> 404 (dark, confirmed). Local access: cd backend-v2 && ./mvnw quarkus:dev.

## Reasons for Scrapping 2026-07-21
Duplicate of [[iron-trainer-1k4b]] (created when the original id didn't print). 1k4b is the canonical BootUI bean.
