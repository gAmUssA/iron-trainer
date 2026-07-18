---
# iron-trainer-2ybd
title: 'backend-v2 readiness: pin the ''today'' timezone to match FastAPI'
status: scrapped
type: bug
priority: high
created_at: 2026-07-16T02:10:32Z
updated_at: 2026-07-18T03:36:33Z
---

ReadinessResource.today() passes today=null → Readiness.compute resolves LocalDate.now() in the JVM default timezone, while FastAPI's readiness endpoint uses date.today() in the Python process tz. If the Quarkus and FastAPI containers run different timezones (or one UTC, one local), the readiness 'today' window differs by a calendar day near local midnight → the same athlete gets a different go-hard/go-easy/rest call from backend-v2 than from FastAPI.

The in-process parity gate CANNOT catch this (both read the same wall clock during the run) — code-review PLAUSIBLE finding.

Fix: pin a single shared date basis for readiness on BOTH backends (e.g. both compute 'today' in a configured race/athlete tz, or both UTC). Add a parity/unit test that pins the zone.

BLOCKS flipping /api/metrics/readiness/today to backend-v2 (bean vqbi enables the flip; do not append readiness to PROXY_PATHS in prod until this is resolved).

Source: code-review of feature/proxy-routing (ReadinessResource.java:38).

## Reasons for Scrapping

Already fixed before this bean was filed. Commit f8c7fa0 "fix(readiness): pin
'today' to UTC on both backends" landed 2026-07-15 22:40 (this bean: 2026-07-16
02:10) under bean iron-trainer-zk7z (completed, same title).

Verified on main:
- Readiness.java:41 `static Clock clock = Clock.systemUTC()`; compute() null-today
  resolves `LocalDate.now(clock)` (UTC). ReadinessResource passes null → UTC path.
- readiness.py: `_utcnow()` / `today_utc()` seam.
- Regression guard exists: ReadinessTest.defaultTodayIsResolvedFromUtcClock()
  asserts Readiness.clock.getZone()==UTC + a frozen-clock cross-midnight test.

No code change needed. Duplicate of zk7z; see also scrapped dup umwz.
