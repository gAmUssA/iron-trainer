---
# iron-trainer-4lve
title: Local login mode — usable with no social login
status: todo
type: epic
priority: high
created_at: 2026-08-18T16:15:38Z
updated_at: 2026-08-18T16:15:38Z
parent: iron-trainer-sgfg
---

A self-hoster must be able to use the app without any social login. Strava OAuth
needs a per-deployment API app (client id + secret the user registers themselves),
and WHOOP/Apple have no self-serve path at all — so "sign in with Strava" cannot be
the front door on a laptop.

## What already exists (verified 2026-08-18)

`AUTH_REQUIRED` defaults to false and `DEFAULT_ATHLETE_ID` defaults to 1. A clean
boot returns `auth_required:false, authenticated:true` from `/api/status` — every
request is implicitly athlete 1. So a no-login single user already works.

That is a **dev-convenience default**, not a designed local mode, and it is one
env-var mistake away from being an unauthenticated production deployment. The work
here is turning an accident into a guarded feature.

## Todo
- [ ] Introduce an explicit `IRONTRAINER_LOCAL_MODE` rather than leaning on
      `AUTH_REQUIRED=false`, so intent is stated rather than inferred
- [ ] **Fail fast on the dangerous combination.** Refuse to boot when local mode is
      on together with any production signal (a non-loopback `CANONICAL_HOST`,
      `COOKIE_SECURE=true`, a public bind). A misconfigured self-host that silently
      serves one athlete's data to the internet is the worst outcome in this
      milestone — louder is better than convenient
- [ ] Guarantee the SaaS deployment can never enable it: assert local mode is off in
      the `%prod` profile used by Railway, and add a test that fails if it is on
- [ ] First-run: name the local athlete, set race + date (currently env-only:
      `RACE_NAME` / `RACE_DATE` default to IM 70.3 NY 2026 — wrong for everyone else)
- [ ] In-app entry of Strava client id/secret so connecting does not require editing
      env files and restarting the container
- [ ] Make the existing admin console reachable in local mode (it is the natural
      operator surface and already covers jobs, sync health and ingest logs)

## Why
Without this, a laptop install is a shell with no data in it. With it, the archive
importers in the sibling epic become the primary way in, and OAuth becomes optional.
