# 0036 — Backend v2: weekly check-in (POST /api/plan/checkin) (2026-07-19)

Date: 2026-07-19
Status: Accepted
Epic: iron-trainer-eom4 (Phase 7) · Feature: iron-trainer-ekyf · Pattern: ADR 0020 / 0035

## Context

The final plan slice — and the composite that **completes the plan vertical**.
`POST /api/plan/checkin` is the one-tap adaptive loop: Strava sync (best-effort) →
match actuals → reconcile/replan next week → readiness (feel-vs-data) →
fitness-tests-due → key sessions — with a human-readable `story`. It composes
almost every vertical ported so far and is what the iOS check-in flow calls.

## What was ported

`plan_router.checkin` + `service.weekly_checkin` + `sanitize_feel` +
`_feel_vs_data_line` + `save_checkin`:

- **`Checkin` entity + `save`** — the previously-unmapped `checkin` table (present
  in the baseline). inputs/readiness → null column when absent.
- **`Checkins`** (pure) — `sanitizeFeel` (clamp energy/sleep/body/stress to 1-5 +
  a ≤280-char note) and `feelVsDataLine` (the byte-parity feel-vs-data sentence).
- **`PlanResource.checkin` / `weeklyCheckin`** — the orchestration + all the
  byte-parity story strings (sync line, compliance %, form + `TSB {:+.0f}`,
  readiness + feel lines, replan delta, tests-due, key sessions). `?async=1` runs
  it as a `checkin` job (s6v3).
- Reused wholesale: `Readiness.compute`/`storyLine`, `FitnessTests.catalog`/
  `RETEST_DAYS`, `StravaSync.runSync`, `reconcileInternal` (extracted), `Compliance`.

## Decisions

- **Strava sync is best-effort:** `StravaSync.runSync` throws a 409
  `WebApplicationException` when not connected (no typed `NotConnected` in v2) —
  caught → `synced=null` + "Strava not connected"; any other error →
  "Strava sync failed"; the loop continues on local data.
- **`feel` threads into the replan** LLM context (`todays_feel`) via
  `reconcileInternal → replanOneWeek → assembleReplan`; only affects the LLM path
  (deterministic template unaffected, so parity holds for `use_llm=false`).
- **"Today"** = `LocalDate.now(ZoneOffset.UTC)` (readiness.today_utc parity).
- **`@Consumes(APPLICATION_JSON)`** so the optional body works (clients send the
  content type even for a no-inputs check-in).

## Testing

- **`CheckinsTest`** pins `sanitizeFeel` clamping + the feel-vs-data strings.
- **`@QuarkusTest`** drives the full loop (sync degrades to not-connected in test),
  no-body, and the async job.
- **`test_plan_checkin_parity`** — checkin is deeply stateful (its `hours_before`
  snapshot + `synced` don't converge across two sequential calls), so it compares
  the CONVERGED fields (`reconcile`, `readiness`, `tests_due`, `key_sessions`) and
  the story minus the Strava + replan-delta lines. CI gate.

## Status: plan vertical complete

All six plan endpoints are now on backend-v2: `GET /api/plan`, `GET
/api/plan/compliance`, `POST /api/plan/{generate, replan-week, reconcile,
checkin}`. Dormant behind the strangler — the plan writes can be added to
`PROXY_WRITE_PATHS` (with idempotency, ADR 0030, already in place). What remains
FastAPI-only is the not-parity-testable LLM/live surface (LLM adaptation quality,
Strava sync/OAuth) and the front door itself.
