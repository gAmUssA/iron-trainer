# 0039 — Backend v2: Strava GDPR archive import (2026-07-19)

Date: 2026-07-19
Status: Accepted
Epic: iron-trainer-eom4 (Phase 7) · Feature: iron-trainer-f6ui · Pattern: ADR 0020

## Context

The last Strava endpoint FastAPI still owned: `POST /api/strava/import` — bulk-load
history from a user's Strava GDPR "Download your data" ZIP, with no live API
connection (bypasses the rate-limit / athlete cap). With this ported, the **entire
Strava surface is on backend-v2** and the vertical can be flipped as one unit.

## What was built

- **`StravaArchive.parse(zip)`** — port of `app/strava_import.py`. Reads
  `activities.csv` (a summary for every activity) and, when a row points at a file
  in `activities/`, parses that `.fit` / `.gpx` / `.tcx` (decompressing `.gz`) for
  power/HR streams to derive normalized power (which the CSV lacks). Any file that
  fails to parse falls back to the CSV summary; a member exceeding 100 MB (or a gzip
  payload that would) is skipped (decompression-bomb guard).
  - **CSV**: a minimal RFC 4180 reader (quoted fields, `""` escapes, embedded
    commas/newlines) → header-keyed rows (Python `csv.DictReader`); `utf-8-sig` BOM
    stripped. Non-ISO `Activity Date` (`"Apr 30, 2021, 1:30:45 PM"`) parsed against
    the same three formats → ISO, else stored as-is. Numbers strip thousands commas.
  - **FIT**: the Garmin FIT SDK (`com.garmin:fit`, already used by the FIT
    *export*) decode path — `Decode` + `MesgBroadcaster` + record/session listeners.
    Record power/HR build the averages; the `session` summary
    (normalized_power/avg_power/avg_heart_rate/max_heart_rate) takes precedence
    exactly as fitdecode's does (truthy-guarded).
  - **GPX/TCX**: StAX (`XMLStreamReader`, DTD/external-entities disabled — XXE-safe
    and native-friendly) scanning by local element name, mirroring the Python
    ElementTree scans.
  - Stream averages use banker's rounding (`Py.roundInt`); NP reuses the shared
    `Metrics.normalizedPower`.
- **`StravaSync.runImport(aid, activities)`** — the post-parse tail, reusing the
  sync building blocks: upsert → prune to the retention window → **local** de-dup
  (no device lookups; there's no token) → seed thresholds → rebuild the PMC, then
  the import summary (`parsed/upserted/with_streams/pruned_old/total_activities/
  duplicates_removed/metrics_days/profile_seeded`).
- **`StravaResource.importArchive`** — `POST /api/strava/import` (multipart
  `file`), port of `strava_router.import_archive`: 401 gate, 413 over the 2 GB cap,
  400 on a non-export ZIP (ValueError parity). `?async=1` runs the parse+import as a
  background job (kind `import`); an import already running → **409** (unlike
  sync/dedup, it does not return the older job — a second archive would be silently
  discarded). The request-scoped upload is copied to a job-owned temp file the job
  parses and then unlinks.
- **Config**: `quarkus.http.limits.max-body-size=2100M` so the handler's 2 GB 413
  is authoritative (multipart streams to disk, so this is a disk not heap bound).

## Testing

- **`StravaArchiveTest`** (pure): a built ZIP with `activities.csv` + `.gpx` +
  `.tcx` + a `.fit.gz` (encoded in-test via the FIT SDK) → asserts CSV
  date/number parsing, GPX/TCX/FIT stream enrichment, and the FIT session-summary
  precedence; plus the no-`activities.csv` → `IllegalArgumentException`.
- **`StravaImportEndpointTest`** (`@QuarkusTest`): multipart upload → 200 + the
  summary shape (dedicated default athlete 5001 satisfies the activities FK); a
  non-export ZIP → 400.
- **Parity** (`test_strava_import_rejects_non_export_parity`): a no-`activities.csv`
  ZIP → 400 on both backends (verified vs real FastAPI + backend-v2). The parse
  happy-path needs real export files (mutates the shared DB), so it's covered by the
  v2 unit tests rather than the shared-DB parity harness.
- Full v2 suite: 166 green.

## Deploy / flip notes

- Import needs no Strava credentials (it's archive-only), so it can flip with the
  rest of the Strava vertical. The whole Strava surface (connect + callback +
  disconnect + sync + dedup + import) is now on backend-v2 — flip them together at
  the front door, wiring `STRAVA_CLIENT_ID/SECRET/REDIRECT_URI` + `CORS_ORIGINS` +
  `ALLOWED_STRAVA_IDS` for the OAuth endpoints.
- The 2 GB body limit is global (Quarkus has no per-route limit); multipart streams
  to disk, matching FastAPI's chunked-to-tempfile handling with the same ceiling.

## Next

Flip the Strava vertical at the front door; then Phase-7 decommission (bean foi1)
is the remaining milestone.
