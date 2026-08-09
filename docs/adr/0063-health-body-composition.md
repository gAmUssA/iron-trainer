# 0063 — Capture body fat % + BMI from Health Auto Export (2026-08-09)

- **Status:** Accepted
- **Bean:** qugv

## Context

Weight is already ingested from Health Auto Export (`weight_body_mass → weight_kg`).
Viktor added Body Fat % and BMI to his Apple Health data and configured HAE to
export them, so the backend should capture those too.

## Decision

Additive, same pattern as the existing metrics:
- **V9 migration** adds nullable `daily_recovery.body_fat_pct` and `.bmi`.
- **`HealthIngest.FIELD`** maps `body_fat_percentage → body_fat_pct` and
  `body_mass_index → bmi` (per-day averaged like weight, not a daily total).
- **Entity** gains `bodyFatPct` / `bmi`; **`HealthResource.applyFields`** writes them
  and `/api/health/recovery` echoes them.

## Notes / open questions

- **Metric names are a best guess.** HAE's exact keys for these weren't in the docs;
  the names follow HAE's existing convention. If they're wrong, the value simply
  lands in `unknown_metrics`, which the admin **Ingests** tab (bean bvif) now
  surfaces with the real key — a one-line map fix. Self-correcting by design.
- **Body-fat unit ambiguity.** HAE may report a 0–1 fraction or a 0–100 percentage.
  We store the raw `qty`; verify against Viktor's first real export via `/recovery`
  and the admin view, and normalize (`*100`) only if needed.
- **Native HealthKit path not touched** — this is the HAE (Health Auto Export) route
  Viktor uses; adding body composition to the native iOS reader is a separate
  follow-up if wanted.
- **Web display:** the Recovery Trends page gains a "Body Composition" section —
  Body Fat % and BMI `MiniSpark`s beside the existing Body Weight chart. Only shown
  when data exists.

## Verification

`HealthIngestTest.parsesBodyComposition` (pure unit, no Docker) — body_fat_percentage
→ body_fat_pct, body_mass_index → bmi, weight still mapped, nothing unknown. Backend
compiles; V9 applies in CI.
