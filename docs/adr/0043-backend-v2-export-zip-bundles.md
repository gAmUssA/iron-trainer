# 0043 — Backend v2: export ZIP bundles (plan.zip + week.zip) (2026-07-20)

Date: 2026-07-20
Epic: iron-trainer-eom4 (Phase 7) · Pattern: ADR 0020

## Context

The last **non-security** Phase-7 endpoints: the multi-file ZIP downloads that let
a user import a whole plan (or one week) into Garmin / TrainingPeaks / Apple
Watch. The per-workout export (`.fit`/`.zwo`/`.itw`) + `plan.itw` already shipped;
this bundles them. Port of export_router.plan_zip/week_zip + service.bundle_zip.

## What was built

- **`GET /api/export/plan.zip`** — the active plan bundled; **404** when there's no
  active plan. **`GET /api/export/week/{week_start}.zip`** — one week's workouts
  (`week_start ≤ date ≤ week_start+6`); **404** when the week is empty; a malformed
  `week_start` propagates as **500** (FastAPI's uncaught `date.fromisoformat`).
- **`bundleZip`** — per workout: `.fit` (all), `.zwo` (bike, when eligible), `.itw`
  (all), plus `IMPORT_INSTRUCTIONS.txt`, reusing the existing `FitExport`/`ZwoExport`/
  `ItwExport` builders. Entry names via **`filename(w, ext)` = `{date}_{sport}_
  {slug(title)}.{ext}`** and the ported `_slug` (non-alphanumerics → `-`, trimmed,
  ≤40, `"workout"` when empty). The README is a byte-for-byte resource copy
  (`import_instructions.txt`, registered for the native image).

## Parity approach

The ZIP container bytes can't match Python's (deflate + timestamps), and the
per-entry bytes intentionally differ too — `.fit` carries a live FileId timestamp
(and v2's spec-correct ms durations, bean sqib), `.zwo` differs in whitespace. So
parity is asserted on what MUST match: the **entry-name set** (`filename()` parity
— the actual new logic), the **README bytes**, and **structural validity** of each
entry (`.fit` signature, `.itw` valid JSON). The per-entry content is separately
parity-tested by the per-workout export tests with the right comparison per type.

## Testing

- **`ExportZipTest`** (`@QuarkusTest`): `slug()` cases vs Python `_slug`; no-active-
  plan → 404 (plan.zip + week.zip); malformed `week_start` → 500.
- **Parity** (`test_export_zip_parity`): plan.zip on both backends → identical entry
  names + README bytes + valid FIT/JSON entries (verified vs real backends).
- Full v2 suite: 186 green.

## Remaining for Phase 7

Device pairing (pairing-code / claim / ingest-token / DELETE tokens) — bearer-token
minting, the last slice. After it, every FastAPI endpoint is ported.
