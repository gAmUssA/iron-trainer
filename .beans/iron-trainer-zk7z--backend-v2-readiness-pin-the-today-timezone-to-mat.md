---
# iron-trainer-zk7z
title: 'backend-v2 readiness: pin the ''today'' timezone to match FastAPI'
status: in-progress
type: bug
priority: high
created_at: 2026-07-16T02:35:03Z
updated_at: 2026-07-16T03:01:14Z
---

ReadinessResource.today() passes today=null → Readiness.compute resolves LocalDate.now() in the JVM default timezone, while FastAPI readiness uses date.today() in the Python process tz. Different container timezones → readiness 'today' window differs by a calendar day near local midnight → different go-hard/go-easy/rest call from backend-v2 vs FastAPI. The in-process parity gate cannot catch this (both read the same wall clock). Fix: pin both backends' readiness 'today' to UTC (matches Railway's UTC containers; footgun-free vs a per-service knob) + tests. Gates flipping /api/metrics/readiness/today to backend-v2 (bean vqbi delivered the mechanism). Source: code-review of feature/proxy-routing (ReadinessResource.java:38, PLAUSIBLE).

## Summary of Changes

Pinned the readiness 'today' to **UTC on both backends** so they always resolve the same calendar day regardless of container timezone (footgun-free vs a per-service knob; matches Railway's UTC containers → no prod behaviour change):

- **backend/app/readiness.py** — added `_utcnow()` seam; both `today or date.today()` defaults (compute + _recovery_flags) now use `_utcnow().date()`.
- **backend-v2 Readiness.java** — added package-visible `static Clock clock = Clock.systemUTC()`; null-today default uses `LocalDate.now(clock)`.
- **Parity seed** (test_parity_exports.py) — seed dates now UTC (`datetime.now(timezone.utc).date()`) to match the apps; was host-local.
- **test_checkin_story** anchor switched to UTC.
- Regression guards: Python `test_utcnow_is_utc` + `test_compute_default_today_comes_from_utcnow`; Java `defaultTodayIsResolvedFromUtcClock` (asserts clock zone == UTC + freezes a fixed UTC instant). These catch a revert to a local-tz clock, which the in-process parity gate structurally cannot.

Unblocks flipping /api/metrics/readiness/today via [[iron-trainer-vqbi]]'s PROXY_PATHS.

208 Python + 34 Java + 19 parity tests green.

## Review follow-up
Code-review found weekly_checkin still resolved 'today' host-local while the endpoint/backend-v2 moved to UTC — same-athlete inconsistency. Fixed: added readiness.today_utc() as the single source; weekly_checkin now uses it (so the check-in story's call matches the daily pill). Other review findings were pre-existing merged proxy code (stale local main polluted the diff) → filed as a hardening task; f0signed already [[iron-trainer-pp3s]].
