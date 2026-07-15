# TrainingPeaks Partner API access — research & draft application

Date: 2026-07-15 · Researched from the request-access form (raw HTML), the
TrainingPeaks Help Center, their partner-API blog post, the PartnersAPI wiki
(OAuth, Pre-Production-Validation, Workouts-Create, Premium-vs-Basic), and
community reports. NOTHING SUBMITTED — drafts below await Viktor's review.

## Headline findings

1. **Applications are PAUSED**: "We are not accepting any new API partners at
   this time" — live on the form since at least Feb 2024. Submitting = joining
   an indefinite waitlist. Contact: api@trainingpeaks.com.
2. **No personal-use access, stated three times** in their docs. Approval
   requires framing Iron Trainer as a commercial product headed for real
   users, honestly ("early-stage, founding athletes, building toward launch").
3. **Premium-athlete requirement**: POST /v2/workouts/plan returns 403 for
   FUTURE planned workouts on Basic (free) athlete accounts. The integration
   is only useful to TP Premium subscribers.
4. **Two-gate process** when active: form → manually issued sandbox
   credentials (sandbox DB wiped weekly) → production promotion via their
   Jira desk with a validation audit (used scopes only, proper User-Agent,
   user-flow diagrams, media-kit-compliant branding on your site).
5. **Access tokens ~600 s** — refresh handling required from day one.
   Scopes are non-inclusive and fixed at grant time.
6. **Fallbacks**: keep the current file export (TP Premium imports structured
   files), or a commercial aggregator (Terra API lists a TP integration).

## Exact form fields + draft answers

| Field | Draft |
|---|---|
| FirstName / LastName | Viktor / Gamov |
| Email | viktor@gamov.io |
| Company | `Iron Trainer` — **decision**: use an LLC name if one exists |
| Website | **decision — needs a real public URL**; repo is private. Stand up a landing page first; they check it (incl. TP branding at validation). |
| Project | Iron Trainer |
| ProjectUrl | landing page (later: App Store URL) |
| Industry | Triathlon |
| MainFocus | Software |
| Objective | Other (they list "pull planned" but not "push planned") |
| Target | Athletes ✓, Coaches ✗ (coach scopes complicate OAuth) |
| Scopes | `workouts:plan` + `athlete:profile` (required pair) + `workouts:read`. **Decision**: add `workouts:details` only if per-second streams wanted. Do NOT over-request — validation strips unused scopes. |

**AboutProject (paste-ready):**
> Iron Trainer is an AI-adaptive training platform for triathletes preparing
> for long-course races (Ironman 70.3 and beyond). It generates fully
> structured swim, bike, and run workouts — warm-up/main-set/cool-down steps
> with power, pace, and heart-rate targets — and continuously adapts the plan
> based on the athlete's completed training, which it reads via an existing
> Strava OAuth integration. The product consists of a FastAPI backend, a
> React web app, and a native iOS companion app. Today Iron Trainer exports
> structured workouts as files that athletes import manually; a direct
> TrainingPeaks calendar integration is the top-requested improvement to
> remove that friction. We are an early-stage product currently in live use
> with founding athletes and are building toward a broader launch for
> self-coached endurance athletes.

**AboutUsage (paste-ready):**
> Our primary use is workouts:plan: after an athlete connects their
> TrainingPeaks account via OAuth, Iron Trainer will push their upcoming
> planned structured workouts (swim/bike/run with step-level power, pace, and
> HR targets using the Workout Structure Object) onto their TrainingPeaks
> calendar, and update or replace those planned workouts when the AI adapts
> the plan. We would use athlete:profile to obtain the athlete ID and zones
> so pushed targets align with the athlete's configured thresholds, and
> workouts:read to read completed-workout summaries to close the adaptation
> loop. Volume will be modest: each connected athlete receives roughly 8–12
> planned workouts per week, written once and updated only when the plan
> changes; reads are on-demand, not bulk polling. We expect a small initial
> user base (tens of athletes) with organic growth. We will develop and fully
> validate the integration in the sandbox environment first, set a proper
> User-Agent, and follow the TrainingPeaks media kit for all branding.

## Technical gotchas (for when access lands)

- Structure passed as an escaped JSON STRING; TP recomputes TSS/IF/duration
  from steps (your values ignored); distance-based steps need the athlete's
  speed threshold set or TSS/time come back empty; workout date ≤1 year out.
- Sandbox DB refreshed from prod every weekend — test data vanishes weekly.
