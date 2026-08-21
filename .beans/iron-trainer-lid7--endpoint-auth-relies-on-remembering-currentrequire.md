---
# iron-trainer-lid7
title: Endpoint auth relies on remembering current.require(); nothing enforces it
status: todo
type: task
priority: normal
created_at: 2026-08-21T14:23:11Z
updated_at: 2026-08-21T14:23:11Z
---

BootUI's security advisor reports "No authentication mechanism configured" (QS-AUTH-001):
no OIDC, no JWT, no basic auth, and **no `@RolesAllowed` anywhere**.

Taken literally that is a false positive — the app has real auth via
`BearerAuthFilter` (device tokens) plus signed session cookies. But the underlying
observation is true and worth taking seriously:

**Endpoint authorization is enforced by every resource method remembering to call
`current.require()`.** There is no framework-level gate. `BearerAuthFilter` only
POPULATES identity; it never rejects. A handler that forgets the call is silently
unauthenticated, and nothing — not a compiler error, not a test, not a startup check
— will say so.

This is not hypothetical: `CurrentAthlete.idOrNull()` exists for logging and returns
null rather than throwing, so the two calls look similar at a glance and only one
is safe for tenancy.

## What would actually help

A guard test, not a framework migration. Something that reflects over JAX-RS resource
methods under `io.gamov.irontrainer` and asserts every one either:
- calls `current.require()` (directly or via a helper), or
- is on an explicit allowlist of deliberately public endpoints (`/api/health`,
  `/api/status`, `/api/strava/callback`, `/privacy`, the OAuth entry points…)

That converts "someone remembered" into "CI checks", which is the whole difference.

## Todo
- [ ] Enumerate the endpoints that are deliberately public today, and why
- [ ] Add the guard test with that allowlist
- [ ] Consider whether the allowlist belongs next to the filter as documentation of
      the security model, rather than buried in a test
