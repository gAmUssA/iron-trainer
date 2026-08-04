# 0053 — WHOOP export-ZIP import + overlay page (2026-08-05)

- **Status:** Accepted
- **Beans:** 48hy (this feature), under epic ids6 (WHOOP integration)

## Context

Epic ids6 wants WHOOP's proprietary **Recovery %** and **Day Strain** overlaid
against Iron Trainer's Apple-Health-derived signals — those two scores never
reach HealthKit, so they can only come from WHOOP directly. The WHOOP **API**
path is gated: instant keys but a hard 10-user cap until app approval
(community-reported 60+ day reviews). The member **data-export ZIP** ("Export
my data" in the WHOOP app, CSVs by email) has no such gate: the member uploads
their own data.

## Decision

**1. Ship the ZIP-upload path first; the API pull (bean 4a6s) upserts into the
same table later.** `POST /api/whoop/import` (multipart) → `WhoopArchive.parse`
→ upsert `whoop_cycles`. Modeled line-for-line on the existing Strava archive
import (`StravaResource.importArchive` / `StravaArchive`); `csvDictRows`/`num`
were made public and reused rather than re-implemented. Sync, no background
job — a multi-year WHOOP export is a few MB of CSV and parses in milliseconds
(Strava needed async because of per-activity FIT/GPX parsing; WHOOP has no
per-day files).

**2. Only `physiological_cycles.csv` is read.** `sleeps.csv`/`workouts.csv` raw
metrics already reach us via HealthKit for members with WHOOP→Apple Health sync
on (WHOOP has written HRV/RHR/sleep/SpO2/temp into Apple Health since 2022) —
re-importing them would double-count. The cycles file carries the two scores
with no HealthKit equivalent plus the WHOOP-side raw dailies needed for
device-vs-device comparison.

**3. Separate `whoop_cycles` table (V4), composite PK `(athlete_id, date)` —
never merged into `daily_recovery`.** WHOOP HRV is **RMSSD**; HealthKit HRV is
**SDNN**. Not interchangeable, so they live in different tables/fields
(`hrv_rmssd_ms` vs `hrv_ms`) and the UI labels both algorithms. The composite
PK + `EntityManager.merge` makes re-uploading a newer export an idempotent
upsert (same days overwritten, new days appended).

**4. Day attribution = local wake date.** Export timestamps are UTC with a
`Cycle timezone` offset column ("UTC-04:00"). A cycle's calendar day is the
date of `Wake onset` shifted by that offset (recovery is scored on waking;
strain accrues through that day), falling back to cycle end, then cycle start,
for open/unscored cycles. Duplicate dates (travel-split cycles): later cycle
wins.

**5. Header-tolerant parsing.** WHOOP has renamed export columns between
versions. `WhoopArchive.col()` matches exactly first, then
case/punctuation-insensitive bidirectional prefix ("Recovery Score" ↔
"Recovery score %", "Heart rate variability" ↔ "Heart rate variability (ms)").

**6. Dedicated WHOOP tab** (`WhoopView.tsx`): upload card (Setup.tsx
hidden-input pattern) + four charts — Recovery % (7-day mean), HRV overlay
(labeled RMSSD-vs-SDNN, "compare trends, not values"), RHR overlay (same unit,
direct comparison), and Day Strain (0–21) vs Strava-derived TSS. Series are
zipped client-side by calendar date from `/api/whoop/cycles`,
`/api/health/recovery`, `/api/metrics/pmc`.

## Consequences

- Data freshness is manual: WHOOP allows one export per 24 h, so the page shows
  day-old data at best until the API pull (4a6s) lands. The table and endpoints
  are shaped so 4a6s only adds a fetcher.
- WHOOP Recovery vs **Iron Trainer readiness** overlay (bean v7dc) is not yet
  possible historically: readiness is computed at read time and never
  persisted. Either persist a daily readiness score or recompute it over the
  recovery history when building that chart.
- Validation against a real member export is pending (bean 48hy todo) — the
  fixture CSVs encode the documented 2026 header set; `col()` tolerance is the
  hedge against drift.
- Workouts/journal from the export are ignored by design; if a WHOOP-only user
  (no Strava, no Apple Health) shows up, revisit reading `workouts.csv`.
