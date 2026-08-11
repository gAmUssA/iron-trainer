---
# iron-trainer-qugv
title: Capture body fat % + BMI from Health Auto Export
status: completed
type: feature
priority: normal
created_at: 2026-08-09T18:14:34Z
updated_at: 2026-08-09T19:29:37Z
---

Weight is already ingested (weight_body_mass → weight_kg). Add body fat % and BMI from HAE, which Viktor now exports.

## Todo
- [ ] V9 migration: daily_recovery.body_fat_pct, daily_recovery.bmi (double precision, nullable).
- [ ] DailyRecovery entity: bodyFatPct, bmi fields.
- [ ] HealthIngest.FIELD: body_fat_percentage → body_fat_pct, body_mass_index → bmi (best-guess HAE names; unknown_metrics in the admin Ingests tab will reveal the real name if off).
- [ ] HealthResource.applyFields (write) + read-echo in /api/health/recovery.
- [ ] Test: ingest a payload with body fat + BMI → row + read echo them.

## Notes
Body fat unit ambiguity (HAE may send 0-1 fraction or 0-100 pct) — store raw qty; verify against the first real export via /recovery + admin, normalize if needed. Uses V9 (V8 reserved for PR #112).
