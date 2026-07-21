---
# iron-trainer-zi1b
title: 'Readiness vertical: /api/metrics/readiness/today + DailyRecovery in backend-v2'
status: completed
type: task
priority: normal
created_at: 2026-07-16T00:01:25Z
updated_at: 2026-07-21T06:13:06Z
parent: iron-trainer-eg0j
---

Port readiness.py: DailyRecovery entity + ACWR compute (acute/chronic windows ending yesterday, TSB modifiers, hard-day streak, recovery flags vs baselines) + /api/metrics/readiness/today. Reason strings must match FastAPI char-for-char — parity gate seeds metrics + recovery to exercise the full call. Highest-value bearer endpoint (iOS).

## Summary of Changes

Ported `app/readiness.py` → backend-v2:
- **DailyRecovery.java** — entity for `daily_recovery` (sleep_h/hrv_ms/rhr_bpm).
- **Py.java** — banker's-rounding helpers (`new BigDecimal(double)` + HALF_EVEN) so reason strings format identically to Python.
- **Readiness.java** — full ACWR compute: acute/chronic windows ending yesterday, TSB/streak modifiers, recovery flags vs the athlete's own baselines, green→easy downgrade (flags lead the reasons).
- **ReadinessResource.java** — `GET /api/metrics/readiness/today`.
- **ReadinessTest.java** — 7 unit tests mirroring test_readiness.py (all pass).

Parity gate: added `seeded_metrics` fixture (test_parity_exports.py) that seeds a steady 42-day load + suppressed-HRV recovery for the bearer's athlete **after** the `seeded` fixture's profile PUT (which calls rebuild_metrics → DELETEs the athlete's rows). Dates computed host-local (date.today()), not Postgres CURRENT_DATE, to stay aligned with the app's tz. test_readiness_parity + test_pmc_parity now exercise real data; `aj == bj` proves Python/Java byte-parity incl. reason strings.

Result: 33 Java tests + 19 parity tests all green (17 readiness unit tests incl. all recovery-flag paths + banker's-rounding ties; parity seeds fractional derived numbers so HALF_EVEN formatting is compared Python↔Java).

## Summary of Changes 2026-07-21
Readiness vertical (/api/metrics/readiness/today + DailyRecovery) ported to backend-v2 and live on the Quarkus front door.
