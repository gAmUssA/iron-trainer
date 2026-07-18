---
# iron-trainer-umwz
title: 'backend-v2 readiness: pin the ''today'' timezone to match FastAPI'
status: scrapped
type: bug
priority: high
created_at: 2026-07-16T02:10:44Z
updated_at: 2026-07-18T03:28:37Z
---

ReadinessResource.today() passes today=null → Readiness.compute resolves LocalDate.now() in the JVM default timezone, while FastAPI readiness uses date.today() in the Python process tz. Different container timezones → readiness 'today' window differs by a calendar day near local midnight → different go-hard/go-easy/rest call from backend-v2 vs FastAPI. The in-process parity gate cannot catch this (both read the same wall clock). Fix: pin one shared date basis on BOTH backends (both UTC, or both a configured tz) + a test. BLOCKS flipping /api/metrics/readiness/today to backend-v2 (bean vqbi enables the flip; keep readiness out of prod PROXY_PATHS until resolved). Source: code-review of feature/proxy-routing (ReadinessResource.java:38, PLAUSIBLE).

Related: [[iron-trainer-vqbi]] delivers the proxy mechanism; this bug gates actually flipping readiness traffic.

## Reasons for Scrapping

Exact duplicate of iron-trainer-2ybd (same readiness 'today' timezone parity bug, same source). Tracking the work in 2ybd.
