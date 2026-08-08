---
# iron-trainer-y2yz
title: 'De-Python backend-v2: retire strangler byte-parity debt'
status: todo
type: epic
priority: normal
created_at: 2026-08-08T23:24:17Z
updated_at: 2026-08-08T23:24:17Z
---

backend-v2 was a strangler port of a now-DECOMMISSIONED FastAPI backend (ADR 0020). FastAPI is gone; backend-v2 is the sole DB reader/writer and iOS/web talk to it over HTTP/JSON only. So every byte-parity / 'mirrors FastAPI' workaround is now dead debt — EXCEPT where it protects live data or a live client contract.

## Load-bearing fact
DB JSON blobs (result_json, inputs_json, structure_json, weeks_json, story_json, readiness_json) have exactly ONE reader now: backend-v2's own parser. Byte/whitespace parity is invisible → free to drop. HTTP JSON key ORDER is insignificant to parsers, but field PRESENCE, null-inclusion, snake_case KEYS, and timestamp string FORMAT are the live iOS/web wire contract → not free.

## KEEP — reclassified as contract, NOT debt (do not 'clean' these)
- util/Py.java rounding/formatting (HALF_EVEN + f-strings): user-facing numbers; swapping rounding = silent output drift across 150+ sites, zero benefit.
- util/Params.java lax bool + int-422: frontend sends ?async=1, ?full=yes (ADR 0029); plain JAX-RS boolean would break them.
- HealthResource /ingest 200-on-bad-JSON + swallowed 401: Health Auto Export automation surfaces non-2xx as errors (ADR 0019/0045) — deliberate live contract.
- JobRunner.submitLock: real single-instance concurrency guard (dedup double Claude/Strava spend), not Python debt.
- SessionCookie itsdangerous VERIFY path: live 14-day browser cookies signed by FastAPI must still verify (see cookie-retire child).
- SpaFallback behavior, BearerAuthFilter last-cookie-wins, StatusResource bool(str): correct as-is.
Reword their 'mirrors FastAPI' comments to state the current contract; don't change behavior.

Research: full catalog with file:line in session; premise confirmed against ADR 0020.
