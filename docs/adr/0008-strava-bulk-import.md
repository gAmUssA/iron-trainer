# 0008 — Strava bulk-export (GDPR archive) importer

- **Status:** Accepted
- **Date:** 2026-06-28
- **Deciders:** Viktor + Claude
- **Builds on:** [0002](0002-multi-user-login-with-strava.md),
  [0007](0007-strava-compliance.md)
- **Research:** [docs/research/strava-ingestion-and-ai.md](../research/strava-ingestion-and-ai.md)

## Context

Strava caps new API apps at a small number of connected athletes and rate-limits the
API, so backfilling full history is slow or blocked. Strava's **GDPR "Download your
data" bulk export** — a ZIP the user requests from their own account — contains the
user's entire history with richer data than the API (per-second power/HR streams,
laps), delivered directly to the user rather than via the API. Letting a logged-in
athlete upload that ZIP bypasses both the cap and the rate limits.

## Decision

Add `POST /api/strava/import` (athlete-scoped) accepting the export **ZIP**. A new
`app/strava_import.py` reads `activities.csv` for every activity's summary, and — for
rows that point at a file in `activities/` — decompresses (`.gz`) and parses the
`.fit` / `.gpx` / `.tcx` for power/HR streams to derive **normalized power** (which the
CSV lacks → accurate bike TSS). Each row maps to a **Strava-API-shaped dict** so the
existing `repo.upsert_activities` path is reused unchanged. `services.import_archive`
mirrors `run_sync`'s post-steps without the API: upsert → prune to the retention window
→ de-dup (no device lookups — no token) → seed thresholds → rebuild metrics, returning
a summary. The endpoint streams the upload to a temp file (no multi-GB ZIP in RAM).
**Scope:** logged-in athlete only; no schema change (`Activity` already has every
field). Frontend: an "Import Strava archive (.zip)" control in the Setup card.

## Alternatives considered

- **CSV-only (no per-activity files)** — simpler, but the CSV has no normalized power,
  so bike TSS would fall back to average power. Chose full parsing for fidelity.
- **gpxpy / python-tcxparser deps** — gpxpy didn't surface the trackpoint extensions
  reliably; switched GPX+TCX parsing to stdlib `xml.etree` (local-name scan), dropping
  a dependency. FIT still uses `fitdecode` (preferring `session`-message summaries,
  falling back to computing NP from the record stream).
- **Background/async job** — deferred; import is synchronous. Large archives are slow
  (flagged below).
- **Import-only / no-Strava-login identity** — deferred (would need a non-Strava auth);
  this importer pairs with the existing Strava OAuth login.

## Consequences

- (+) Full history in one upload, bypassing the athlete cap + rate limits; richer
  (NP/streams) than the API for bike TSS.
- (+) Reuses the entire downstream pipeline (`upsert_activities` → dedup → infer →
  metrics) — no schema or metrics changes.
- (−) Synchronous: a multi-GB / many-year archive can take a while and ties up a
  request. Follow-up: background processing + progress.
- (−) **Does not address §5.3** — this importer only loads activities for
  display/metrics; it does **not** feed the AI planner, and the app still uses the
  Strava API for login/sync. The "export-only, zero-API → feed AI" path remains a
  separate decision (see the research doc + ADR 0007).
- New deps: `python-multipart` (uploads), `fitdecode` (FIT). GPX/TCX via stdlib.

## Implementation notes

`app/strava_import.py` (`parse_archive` + per-format parsers), `app/metrics.py`
(`normalized_power`), `app/services.py` (`import_archive`), `app/routers/strava_router.py`
(`POST /api/strava/import`, UploadFile → tempfile). Frontend: `api.importArchive`
(FormData), `components/Setup.tsx` (`ConnectCard` upload + summary). `pyproject.toml`
deps.

## Verification

`tests/test_strava_import.py`: `normalized_power` math; `parse_archive` maps a CSV row
with a `.gpx.gz` file (streams → power/NP/HR) and a no-file row (CSV summary only);
the `POST /api/strava/import` endpoint loads + rebuilds metrics; a non-export ZIP →
400. 78 backend tests pass; ruff clean; frontend builds; Import control verified
in-browser. FIT-binary path verified manually with a real export (handcrafting `.fit`
is impractical; GPX + CSV cover the parsing logic).
