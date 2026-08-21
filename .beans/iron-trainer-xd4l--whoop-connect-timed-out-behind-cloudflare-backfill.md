---
# iron-trainer-xd4l
title: WHOOP connect timed out behind Cloudflare; backfill ignored existing ZIP data
status: completed
type: bug
priority: high
created_at: 2026-08-21T18:39:54Z
updated_at: 2026-08-21T18:39:54Z
parent: iron-trainer-4a6s
---

Connecting WHOOP in production returned a **Cloudflare 524** to the athlete while the
backend quietly succeeded. Both stamped the same second:

```
18:28:08  WHOOP connected: athlete=2
18:30:13  GET /api/whoop/callback ... 200        <- backend finished
18:30:13  Cloudflare "A timeout occurred" 524    <- what the athlete saw
```

The tokens were stored and the data synced. Only the `?whoop_connected=1` redirect
was lost, so the UI never learned it had worked.

## Two independent mistakes, stacked

**1. The callback ran the sync INLINE.** No amount of tuning saves this: Cloudflare's
edge timeout is 100s and it is not configurable on the free tier. Any first sync
long enough to matter loses the redirect.

**2. It walked five years unconditionally**, ignoring that the athlete had already
uploaded the export ZIP — years of days sat in `whoop_cycles` and were re-fetched
anyway. ~180 paged requests at the 700ms pacing WHOOP's 100/min cap forces is ~128s
of pure sleeping, to rewrite rows that were already correct.

Fixed both: `runCatchUp` starts from the newest stored day (any source — a ZIP day
is a covered day), bounded by the history window, with a 3-day overlap because WHOOP
rescores recent days and an export's final day is often partial. And it runs through
JobRunner, so the redirect is immediate and `/api/whoop/status` reports progress.

## What is still worth doing

- [ ] **Decide whether a full API backfill over ZIP data is worth offering at all.**
      It is now an explicit "Full re-sync" button rather than something inflicted on
      every connect, which is the right shape. But nobody has checked whether API
      rows are actually BETTER than the ZIP rows they overwrite for the same day.
      V11 gives `api` precedence over `zip` on the assumption they are. If that is
      wrong, the button is a slow way to make data worse.
- [ ] Related, and still open from mfm9: confirm the `sleep.cycle_id` join key
      against real ZIP overlap. Now genuinely testable — prod has both sources in
      one database for the first time. If the derivation is wrong, ZIP and API rows
      for the same night land on dates one apart instead of merging.
- [ ] Consider whether the daily job should use `runCatchUp` too. Today it uses the
      fixed 3-day incremental, which silently never repairs a gap longer than that —
      after a multi-day outage those days stay missing forever.
