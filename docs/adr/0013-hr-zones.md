# ADR 0013 — HR zones: calculator, zone-based prescriptions, planner integration

**Status:** Accepted · 2026-07-09

## Context

The app stored threshold HR and max HR but used them only for TSS costing.
Workout prescriptions were power-only (bike) and pace-only (run/swim) — an
athlete without FTP or a threshold pace got *empty* targets — and neither the
template nor the LLM planner spoke in heart-rate zones, the vocabulary most
age-group athletes actually train by. Viktor asked for zones in planning, zones
fed to the AI, and a zone calculator.

## Decisions

1. **`app/zones.py`, pure functions.** Five-zone model from LTHR (Coggan
   fractions: Z1 <81%, Z2 81–89%, Z3 90–93%, Z4 94–99%, Z5 100–106%), falling
   back to classic %max-HR bands when only max HR is known; Z5 capped at max HR
   when available. The zone vocabulary maps 1:1 onto the planner's existing
   intensity levels (recovery→Z1 … vo2→Z5), so no second taxonomy.
2. **Calculator = derivation, not storage.** `GET /api/athlete/zones` computes
   from current thresholds; the web Thresholds tab renders the table and states
   its basis ("from your threshold HR" vs "estimated from max HR — set an LTHR
   for sharper zones"). Nothing to migrate, nothing to go stale.
3. **Template prescriptions are zone-annotated and HR-fallback.** Every workout
   description now carries its zone and bpm band ("tempo (Z3 · HR 144–149
   bpm)"), which flows to the web plan, the iOS Today view, and every export.
   When the primary threshold is missing (bike without FTP, run without
   threshold pace), the step targets themselves become HR ranges — which the
   iOS app already converts to Apple Watch heart-rate alerts. Swim stays
   pace-only (HR is impractical mid-pool).
4. **LLM planner gets zones + instructions.** Both `adjust_season` and
   `generate_week_workouts` prompts now include the computed zone table and an
   explicit instruction to anchor intensity to Z1–Z5, name the zone in each
   description, and prescribe HR-range targets where power/pace is missing.

## Consequences

- Regenerating (or any future-week refresh from a threshold change, ADR-0012-
  era behavior) re-derives descriptions/targets — zone labels appear on
  existing plans automatically after the next threshold save or regenerate.
- The %max-HR fallback is coarser than LTHR zones by design; the UI says so
  and nudges toward an LTHR test (the Tests tab already measures it).
- LLM prompt size grows by ~200 bytes; no schema changes.

## Verification

`tests/test_zones.py` (7 tests): band math against Coggan fractions, Z5 max-HR
cap, %max-HR fallback + empty case, intensity→zone mapping, endpoint, and two
plan-level tests — HR targets + Z-labels when FTP/pace are absent, power/pace
retained (with zone annotation) when present. Full suite 155 green; frontend
build green; calculator card verified visually against a seeded backend.
