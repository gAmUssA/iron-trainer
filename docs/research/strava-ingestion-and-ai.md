# Research: Strava bulk-export ingestion + AI plan generation

Status: research / decision input. Last updated 2026-06-28.

> ⚠️ The legal/policy sections below are an engineering reading of public Strava
> terms, **not legal advice**. The §5.3 AI question is genuinely unsettled (an
> identical question sits unanswered on Strava's own developer forum). Confirm with
> Strava developer support in writing and with counsel before any commercial or
> multi-user launch.

Two questions, which turn out to be connected:
1. Support **bulk loading a user's Strava GDPR data export** (sidesteps the API's
   connected-athlete cap + rate limits, and gives richer data).
2. Find a way to **derive features from Strava data to feed an AI** for plan
   generation — without breaking Strava's API terms.

The link: the AI prohibition is keyed to data obtained **via the API**; the GDPR
export is not obtained via the API — so it is the one channel that is textually
outside that prohibition.

---

## 1. Strava GDPR bulk export — importer feasibility

### How the user gets it
Strava.com → Settings → **My Account** → "Download or Delete Your Account" → **Get
Started** → **Request your archive**. Strava emails a download link (minutes–~24h).
Web only; it's the user's **GDPR data export**, delivered directly to them — it never
touches the Strava API. It is a **point-in-time snapshot** (no incremental/delta).

### Archive structure
- `activities.csv` — master index, one row per activity, ~80+ (mostly sparse) columns.
  - **Set export language to English** or headers are localized and parsing breaks.
  - `Activity Date` is human text (e.g. `Apr 30, 2021, 1:30:45 PM`) — **not** ISO-8601.
  - Several names (`Distance`, `Elapsed Time`, `Max Heart Rate`) appear **twice** with
    possibly **different units** — bind by header, validate ranges, don't trust order.
  - Load-bearing columns: `Activity ID`, `Activity Date`, `Activity Name`,
    `Activity Type`, `Elapsed Time`, `Moving Time`, `Distance`, `Average/Max Heart Rate`,
    `Average/Max Watts`, `Average Speed`, `Elevation Gain`, `Calories`, `Filename`,
    `Activity Gear`.
- `activities/` — one raw file per activity: `*.fit.gz`, `*.gpx`, `*.tcx`, `*.gpx.gz`
  (format = whatever was originally uploaded).
  - Linked via the **`Filename`** column — note the file's numeric id ≠ `Activity ID`
    (known gotcha). Match on `Filename`, not the id.
  - **Non-GPS activities** (treadmill, weights, manual) have an **empty `Filename`** →
    ingest from the CSV row alone, no file to parse.
- Other CSV/JSON (varies by account): `profile`, `bikes`/`shoes`/gear, `components`,
  `clubs`, `routes`, `media/`, social. Only `activities.csv` + `activities/` are needed.
- **Size:** CSV is tiny; `activities/` + `media/` dominate — hundreds of MB to a few GB
  for years of history. Stream-extract; don't load the whole ZIP into memory.

### Richer than the API
Full **per-second streams** (HR, power, cadence, GPS, altitude, temp), **laps**, and
**device info** are embedded in the raw FIT/TCX files — all without spending API quota.
(FIT richest; TCX has power; GPX has recorded-but-not-estimated power.)

### Parsing (Python)
- `.fit.gz` / `.gpx.gz` → stdlib `gzip` (stream into the parser).
- `.fit` → **`fitdecode`** (preferred; modern `fitparse` rewrite) — `record` msgs for
  trackpoints, `lap` for splits, `device_info`/`file_creator` for device.
- `.gpx` → **`gpxpy`** (HR/cadence/power in `<extensions>`).
- `.tcx` → `tcxreader` / `python-tcxparser` (the latter for quick summary metrics).
- CSV: UTF-8; explicit non-ISO date parse; English headers; handle null-heavy rows.

### How others do it
Runalyze recommends the bulk export for history import "as it contains the original
FIT files," then dedupes against its live connection. `fit2gpx` (`StravaConverter`)
and `ParseStravaBulkExport` are good reference implementations of the
CSV-index → file-linkage → decompress → parse pattern.

### Integration into Iron Trainer
- Entry point already exists: parse each activity to a **Strava-shaped dict** and call
  `repo.upsert_activities(raw_list)` (`backend/app/repo.py:225`). It maps via
  `_map_activity` and dedupes in-batch by `id`; downstream `services.deduplicate()` +
  `repo.rebuild_metrics()` + `analysis.infer_profile` already handle the rest.
- Dict shape `upsert_activities` expects (from `_map_activity`, `repo.py:201`):
  `id`, `sport_type`|`type`, `start_date_local`|`start_date`, `moving_time`,
  `elapsed_time`, `distance` (m), `average_watts`, `weighted_average_watts`,
  `average_heartrate`, `max_heartrate`, `device_watts`, `name`, `average_speed` (m/s),
  `total_elevation_gain` (m).
- **No import path exists today** — `pyproject.toml` has `fit-tool` for *export* only;
  no `fitparse`/`fitdecode`/`gpxpy`/TCX, no upload endpoint. New work = upload endpoint
  + ZIP/CSV/FIT/GPX/TCX parser → dict → `upsert_activities`.
- Dedup by `Activity ID`; treat each export as a full snapshot, not a delta.

**Verdict:** straightforward, genuinely useful (bypasses the athlete cap + rate
limits, richer data), low-risk to build.

---

## 2. Deriving features for AI — the §5.3 problem

### What we send to Claude today
`backend/app/planning/llm.py` already sends **only derived/aggregated** data —
thresholds (`ftp`, `threshold_hr`, `max_hr`, `threshold_pace_run`, `css_swim`),
`CTL/ATL/TSB`, weekly volume, compliance — **never raw activities**. Important: this
does **not** make it compliant (see below).

### §5.3 forecloses the obvious approach
From the Strava API **Policy** (incorporated into the Agreement):

> §5.3 — "You may not use the Strava API Materials or Strava Data … in connection with
> the development, training, evaluation, or operation of any AI Application. This
> prohibition extends to: **Any data derived from, aggregated from, anonymized from, or
> generated using Strava Data, in any form** … and … **ingestion into a context window
> or working memory**, … retrieval-augmented generation …"

So **deriving / sampling / summarizing API-obtained Strava data and feeding *that* to
an LLM is still prohibited** — there is no laundering path. A single Claude inference
call is "operation of an AI Application" via "ingestion into a context window."
Reinforced by §5.4 (no analytics on Strava data "even … aggregated, de-identified, or
anonymized," and no combining with other data).

### The one textual escape — and why it ties to Item 1
"Strava Data" is **defined** as data obtained via the API:

> §2.3(i) — "'Strava Data' means all data you access or collect from the Strava API
> Materials …"

§5.3, §5.4, §6.2 (7-day cache), §7.4 (deletion) all key off "Strava Data." The **GDPR
bulk export is not obtained via the API → not "Strava Data" → those clauses don't reach
it by their terms.** That is the textual basis for "export-fed AI is outside §5.3."

### Why the escape is fragile (not a safe harbor)
1. **Contamination** — the argument requires the product to use **zero** Strava API
   (no OAuth login, no sync, no webhooks). Any API use binds the Agreement, and an
   "export only for the AI part" split looks like deliberate circumvention. Iron Trainer
   is currently entirely API-based — this is the core tension.
2. **Consumer ToS** independently restricts even export data: §12 personal-use-only,
   §19 no derivative works of "Content" / no automated collection. A **commercial** app
   turning a user's export into a paid plan is exposed regardless of the API.
3. **Intent** — the Nov-2024 API update and the **Strava MCP** carve-out (§3.5: the
   only sanctioned "bring your own AI to your own data," **personal-use only, not
   commercial**) show Strava means to gatekeep AI-on-your-data.
4. **"AI Application" is undefined** in the Agreement/Policy — ambiguity, not a shield.

### Community / precedent
An on-point thread ("AI inference with Strava data — is it prohibited?") is **open and
unanswered** by Strava. GDPR-export ingestion is discussed but neither blessed nor
condemned. No enforcement precedent either way.

---

## Recommendations

1. **Build the bulk-export importer regardless** — solves the athlete-cap + rate-limit
   pain, richer data; ingestion itself is low-risk. (Plan: ADR 0008.)
2. **Cleanest AI path uses the Fitness Tests feature:** plan-gen from **user-entered**
   thresholds (FTP/LTHR/CSS from the Tests library) + manual volume targets — these are
   **not Strava-derived**, so feeding them to Claude is outside §5.3 entirely. Keep
   Strava (API *or* export) for **display/metrics only**, never the LLM.
3. The fully-defensible "export-only + zero API" path exists but conflicts with wanting
   Strava login/sync — a real architecture fork to decide deliberately.
4. **Get written clarification from Strava** (cite the open forum thread) + counsel
   before any commercial/multi-user launch. Until then, treat third-party AI ingestion
   of Strava-origin data (API *or* export) as a live compliance risk.

See also [docs/strava-compliance.md](../strava-compliance.md) and ADR
[0007](../adr/0007-strava-compliance.md).

## Sources
- [Strava API Agreement](https://www.strava.com/legal/api) — §1.1, §2.3(i)
- [Strava API Policy](https://www.strava.com/legal/api_policy) — §3.5, §5.3, §5.4, §6.2, §7.4
- [Strava Terms of Service](https://www.strava.com/legal/terms) — §12, §19
- [Exporting your Data and Bulk Export](https://support.strava.com/hc/en-us/articles/216918437-Exporting-your-Data-and-Bulk-Export)
- [Updates to Strava's API Agreement (Nov 2024)](https://press.strava.com/articles/updates-to-stravas-api-agreement)
- [Community: AI inference with Strava data — is it prohibited?](https://communityhub.strava.com/developers-api-7/ai-inference-with-strava-data-is-it-prohibited-under-the-new-api-agreement-13256)
- [Community: bulk file ids don't match activities.csv Activity ID](https://communityhub.strava.com/developers-api-7/strava-bulk-data-activity-file-ids-don-t-match-the-activities-csv-activity-id-1805)
- [fitdecode](https://pypi.org/project/fitdecode/) · [gpxpy](https://github.com/tkrajina/gpxpy) · [fit2gpx](https://github.com/dodo-saba/fit2gpx) · [Runalyze import](https://runalyze.com/help/article/strava?_locale=en)
