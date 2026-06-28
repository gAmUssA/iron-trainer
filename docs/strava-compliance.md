# Strava API compliance

Status of Iron Trainer against Strava's
[Brand Guidelines](https://developers.strava.com/guidelines/) and
[API Agreement](https://www.strava.com/legal/api_policy). Last reviewed 2026-06-27.

> ⚠️ This is an engineering summary, not legal advice. The §5.3 / §6.2 items below are
> interpretations of the agreement and should be confirmed with Strava — especially
> while an athlete-capacity increase is under Strava review.

| Obligation | Status | Notes |
|---|---|---|
| Official "Connect with Strava" button (brand kit artwork) | ✅ done* | `frontend/public/strava/btn_strava_connect.svg` on login + Setup. *Faithful recreation — **swap in the exact brand-kit file before public launch** (see that folder's README). |
| "Powered by Strava" attribution on data views | ✅ done* | App footer (`App.tsx`). Same swap-in caveat. |
| "View on Strava" attribution + link treatment (#FC5200) | ✅ done | Connected-account row links to the athlete's Strava profile. Per-activity links: add when an activity-list UI exists. |
| Marks not modified/animated; not used as app icon | ✅ | Our app icon is unrelated; marks unmodified. |
| Friendly OAuth error handling | ✅ done | Callback redirects to the SPA with `?strava_error=`; banner shown. Strava's own "limit exceeded" 403 renders on strava.com — we add a capacity hint on the login screen. |
| §2.3 Show a user's data only to that user | ✅ | Every query scoped by `athlete_id` (`current_athlete_id()`). |
| §7.4 Delete Strava + derived data on deauthorize | ✅ done | `POST /api/strava/disconnect` → `/oauth/deauthorize` + purge activities + `MetricDaily` + tokens. |
| §2.5 Written confirmation of deletion | ✅ done | Disconnect returns + displays a deletion summary. |
| §6.2 No caching Strava data > 7 days | ⏳ deferred | We currently retain synced activities indefinitely. Needs a purge + rework of threshold inference / CTL windows to run off short-lived raw data. **Follow-up.** |
| §5.3 No Strava Data with the operation of an AI app | ⚠️ flagged | Plan generation sends Strava-derived data to Claude (an LLM). Appears to conflict with §5.3. **No change made this pass** (decision: flag + revisit after Strava's review). Mitigation options: decouple AI from Strava data (generate from manual thresholds only), or seek written clarification from Strava. |
| §3 Rate limits | ✅ | Incremental sync; bounded de-dup; respects limits. |

## Connected-athlete limit
New Strava API apps are capped at **1 connected athlete** (the owner). Adding a second
athlete returns Strava's own **"403: Limit of connected athletes exceeded"** (not our
allowlist). Fix: request a higher limit via the Developer Program form / developers@strava.com
(see [rate-limits → Athlete Capacity](https://developers.strava.com/docs/rate-limits/)).
Our `ALLOWED_STRAVA_IDS` allowlist is a separate, additional gate on top of Strava's cap.
