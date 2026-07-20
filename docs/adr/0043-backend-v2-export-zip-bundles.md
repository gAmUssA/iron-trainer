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

## Code-review fixes (applied before merge)

3 CONFIRMED + 1 PLAUSIBLE fixed:
1. **Duplicate entry names** — two workouts with the same (date,sport,title) made
   `putNextEntry` throw (500) where Python's `writestr` tolerates it (200); now
   de-duplicated (keep first) → a valid 200 zip.
2. **Compact-ISO week_start** — `LocalDate.parse` rejected `"20260705"` that
   `date.fromisoformat` (3.11+) accepts → 500; now parses extended OR basic ISO
   (the raw string still drives the filter → 404 for a compact form, like Python).
3. **`filename` null date/sport** — renders `"None"` (Python f-string), not `"null"`.
4. **README load decoupled** — lazy (not a static-final initializer) so a
   resource-load failure can't 500 the already-shipped .fit/.itw/.zwo/plan.itw.

v2 suite 186 green; export-zip parity re-verified vs real backends.
