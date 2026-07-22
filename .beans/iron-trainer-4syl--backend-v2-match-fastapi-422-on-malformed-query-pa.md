---
# iron-trainer-4syl
title: 'backend-v2: match FastAPI 422 on malformed query-param coercion'
status: scrapped
type: task
priority: low
created_at: 2026-07-16T17:23:47Z
updated_at: 2026-07-22T17:05:07Z
---

FastAPI returns 422 for a non-numeric int query param (e.g. /api/tests/{slug}/prefill?limit=abc, /api/metrics/pmc?days=xyz). backend-v2 binds these as raw JAX-RS @QueryParam int, so a coercion failure yields 404/400, not 422 — a divergent error contract on malformed input. Cross-endpoint (PMC days, prefill limit, and any future int query params). Fix once with a JAX-RS ParamConverter/ExceptionMapper (or String param + manual parse → 422) rather than per-endpoint. Low priority: real clients don't send malformed params; no parity test covers it. Source: code-review of feature/fitness-tests-reads (FitnessTestsResource.java:84), consistent with existing PmcResource pattern.

## Scrapped (2026-07-22): FastAPI parity retired; backend-v2 is source of truth (no need to match FastAPI's 422).
