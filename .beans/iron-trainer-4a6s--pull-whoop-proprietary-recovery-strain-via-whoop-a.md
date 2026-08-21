---
# iron-trainer-4a6s
title: Pull WHOOP proprietary Recovery % + Strain via WHOOP API (distinct source)
status: todo
type: feature
priority: normal
created_at: 2026-07-29T20:38:25Z
updated_at: 2026-08-21T04:00:18Z
parent: iron-trainer-ids6
blocked_by:
    - iron-trainer-mfm9
---

The ONLY thing worth pulling directly from WHOOP: its proprietary Recovery % (+ Strain) — NOT in Apple Health. Store as a distinct metric, source='whoop_api'. Do NOT pull raw HRV/RHR/sleep from WHOOP (already ingested via HealthKit — would double-count).

## Todo
- [ ] WHOOP dev app: create at id.whoop.com → Client ID/Secret (self-serve; ≤10 users until approval — fine for personal/beta).
- [ ] OAuth 2.0 Authorization Code + offline scope: iOS ASWebAuthenticationSession (or web redirect) → backend (Quarkus) holds secret, code→token exchange, store + ROTATE refresh tokens (single-use). Scopes: read:recovery, read:cycles.
- [ ] Pull layer: GET recovery (via Cycle endpoints) — recovery_score, day strain; cursor pagination (nextToken, start/end). Respect 100/min + 10k/day.
- [ ] Store as whoop_recovery / whoop_strain with source='whoop_api'; reuse daily_recovery-style schema + a source column.
- [ ] (Later) webhooks (recovery.updated, HMAC-SHA256 verify, fetch-on-notify) — start with a daily/hourly poll; add webhooks only if latency matters.
Effort ~3-5 days. Auth token: ~1h access, rotating refresh.

## Research 2026-08-20 — API surveyed, and this bean's plan needs amending

Primary docs at developer.whoop.com fetched live. Full report in the session transcript;
the parts that change this bean:

### AMENDMENT: do NOT add a separate `whoop_recovery` / `whoop_strain` table

This bean currently proposes new tables with a `source` column. That forks the data model
and breaks `WhoopInsights`, `WhoopResource.cycles`, `WhoopView.tsx` and the `WhoopDay`
type, all of which read `whoop_cycles`. **Write into `whoop_cycles`**, adding `source`
(`'zip'`|`'api'`, existing rows backfilled to `'zip'`), `api_updated_at`, and
`whoop_cycle_id`.

### The export ZIP can NEVER be retired — settled

**The v2 API has no journal endpoint.** The complete collection list is cycle, recovery,
sleep, workout, profile, body measurement. `whoop_journal_entries` (V6) is fed solely by
`journal_entries.csv`, and `WhoopInsights.behaviors()` — the behavior-correlation feature
— is built entirely on it. So the ZIP stays permanently as the journal source and as the
no-OAuth fallback, regardless of how deep API history turns out to be. Its UI label
should change from "fallback" to "upload your export to unlock behavior correlations".

### Skip webhooks entirely

Six events exist, but: callbacks need public HTTPS (impossible for self-host behind NAT),
WHOOP retries only ~5 times over ~1 hour then drops, and WHOOP's own docs say delivery is
not guaranteed and you should run a reconciliation poll anyway.

The stronger argument is that they buy nothing even for SaaS: an incremental poll is
**3 requests**, so hourly polling costs ~96 req/day/user ≈ 1% of the 10k daily app budget,
against a metric that updates once each morning. Webhooks would add HMAC verification, a
public endpoint, a trace_id dedup store, a reconciliation job anyway, and a second write
path into the same table. Recommend never building them.

### Skip gap-run detection too

A full five-year re-scan is ~232 requests / ~2.4 minutes / 2.3% of the daily budget. Two
modes mirroring `StravaSync.runSync(aid, full)` are simpler and self-healing:
- **full**: walk from `today − history-years` (reuse `irontrainer.history-years`). The
  upsert predicate makes re-running free. *This is the gap detection.*
- **incremental**: `start = max(stored api date) − 3 days`, because WHOOP re-scores after
  the fact and there is **no `updated_since` filter anywhere in the API**. The 3 days is
  a guess — no doc states the re-scoring window.

### Dedup rule — the join key

`whoop_cycles` is keyed `(athlete_id, date)` where date is the **local calendar date of
wake onset** (`WhoopArchive.rowToCycle`, verified). The API path must reproduce that
exactly or you get two rows per physiological day:

> `date` = local date of the associated sleep's `end` (wake onset), shifted by the cycle's
> `timezone_offset`; fallback ladder sleep-end → cycle-end → cycle-start, mirroring
> `WhoopArchive`.

Fetch the sleep collection paginated and index by `cycle_id` (filter `nap == false`) —
do NOT call `/v2/cycle/{id}/sleep` per cycle, that is ~1,826 extra requests for the same
data. `timezone_offset` arrives as `"-04:00"`, which `ZoneOffset.of` parses directly.

### Dedup rule — precedence

Rank by source, not wall-clock (the ZIP has no per-row `updated_at`):

```sql
ON CONFLICT (athlete_id, date) DO UPDATE SET ...
WHERE whoop_cycles.source IS DISTINCT FROM 'api'
   OR (EXCLUDED.source = 'api'
       AND EXCLUDED.api_updated_at >= whoop_cycles.api_updated_at)
```

The load-bearing case is **stored `api` + incoming `zip` → skip**: a stale ZIP re-upload
must never clobber fresher API rows. This is the analogue of the `mg1n` lesson — there the
values were cumulative so it needed daily-MAX; here the values are not, so the guard is
**max-of-observation, never max-of-value**.

`WhoopResource.importArchive`'s delete-by-day-chunk needs `and source <> 'api'` added, or
it deletes API rows before reinserting ZIP ones.

### Second dedup rule — the score-state guard

Polls routinely catch `PENDING_SCORE` (this morning's recovery isn't scored yet) or
`UNSCORABLE`. Writing those would blank a previously-`SCORED` day with nulls, and the
timestamp check would NOT stop it because `updated_at` is genuinely newer.

> Only `score_state == SCORED` may write metric columns. A pending cycle may create a date
> row if none exists, but must never null out existing values.

### VERIFIED BUG RISK — timestamp format mismatch

`cycle_start` holds the ZIP's `"yyyy-MM-dd HH:mm:ss"`, and `WhoopInsights.minutesOfDay`
parses with exactly that formatter, **returning null on failure** (confirmed at
`WhoopInsights.java:171-182`, formatter at :28). The API emits ISO-8601. Dropping raw ISO
into `cycle_start` makes **bedtime consistency silently degrade to fewer nights or null,
with no error logged anywhere**. Either normalize on write, or have the API writer not
touch `cycle_start`/`cycle_end` at all.

### Other facts worth having

- v2 is current; v1 webhooks removed 2025-11-01; **no published v1 REST sunset date**.
- Rate limits are **per API key**: 100/min, 10k/day. For SaaS the 10k/day app cap bites
  before the 10-user approval cap does (~100 users hourly saturates it). For self-host it
  evaporates — each install has its own app and its own budget.
- Self-serve app registration, no approval needed up to **10 members**; approval beyond
  that is documented as monthly cadence but community threads show 60+ days unanswered
  (see rhky).
- Refresh tokens are **single-use and rotate**, and concurrent refreshes fail —
  `StravaTokens`' `@Transactional` refresh-and-persist is the right pattern.

### Blocked on

Bean **mfm9** — three unknowns (localhost redirect URI acceptability, historical reach,
sleep→cycle direction). Do that first; two of the three change the design.

## Goal set by Viktor 2026-08-21

- **Batch (export ZIP) = initial import.** Stays permanently — it is also the only
  source of journal entries (the API has no journal endpoint), so it is not merely a
  bootstrap.
- **API = incremental job, daily at 10:00.** Not hourly. Recovery is scored once each
  morning, so a single 10:00 run costs ~4 requests/day and catches the day's data.
  Confirms the research's "skip webhooks" recommendation with even more margin.

### Probe status
- **Q1 ANSWERED: localhost redirect URIs are accepted.** Verified in the WHOOP
  developer dashboard on 2026-08-21 — `http://localhost:8080/api/whoop/callback`
  passed form validation (the only errors raised were Privacy Policy URL and Contact,
  both since supplied). Self-hosters can therefore do a normal OAuth round-trip; the
  paste-the-URL-back fallback is NOT needed.
- The app is registered with scopes `read:recovery`, `read:cycles`, `read:sleep`.
  Credentials are in `.env` as `WHOOP_CLIENT_ID` / `WHOOP_CLIENT_SECRET`.
- Q2 (historical reach) and Q3 (sleep -> cycle direction) still open; both need a live
  token, so they are answered during Phase 2 shadow-mode rather than before Phase 1.

### Privacy policy
Published at https://irontrainer.app/privacy (PR #126) — required for the WHOOP app.
