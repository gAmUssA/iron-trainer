# 0052 — Sign in with Apple (web) + account linking + canonical domain (2026-07-29)

- **Status:** Accepted
- **Beans:** 3e6w (Sign in with Apple), 4uj1 (reverse link, deferred)

## Context

The app authenticated users only via Strava OAuth. App Store Guideline 4.8 and,
more practically, athletes who don't use (or don't want) Strava need a
privacy-preserving, Strava-free way in. We added **Sign in with Apple** as a
parallel identity provider — web-first (the iOS app authenticates via device
pairing, so its in-app SIWA button is deferred — see Consequences).

## Decision

**1. Web flow = Sign in with Apple JS → id_token → JWKS verify → session cookie.**
The browser runs Apple's JS SDK (`AppleID.auth`, popup) and posts the returned
`id_token` to `POST /api/auth/apple/web`. `AppleAuth` verifies it against Apple's
JWKS (nimbus-jose-jwt), checking `iss`/`aud`/`exp` and reading the stable `sub`.
On success the backend mints the same itsdangerous **session cookie** the Strava
web login uses. A native endpoint (`POST /api/auth/apple`) mints a device bearer
from the same verify path.

**2. Multi-audience.** `apple.audiences` accepts both the native bundle id
(`io.gamov.irontrainer.helper`) and the web **Services ID**
(`io.gamov.irontrainer.web`); the web `id_token`'s `aud` is the Services ID. The
web id is in the *default* so the flow doesn't hinge on an env var.

**3. Account linking is link-only for authenticated callers, or 409.**
`resolveAthlete(apple, linkTarget)`: if the caller presents a genuine credential
(`linkTarget` resolved from a real bearer/session — never the no-auth default
athlete), the call LINKs Apple onto that athlete or fails **409** — it never mints
a bearer / Set-Cookie for a *different* athlete (which would silently strand the
caller's real account). Unauthenticated callers log in to the Apple-bound athlete
or create a fresh Strava-free one. `sessionLinkTarget` parses the `session` cookie
with the same last-occurrence semantics as `BearerAuthFilter`.

**4. Flyway on in prod (baseline V2).** `apple_user_id` (V3, partial unique index)
is the first schema change to auto-apply: `migrate-at-start=true` with
`%prod.baseline-on-migrate=true` + `baseline-version=2`, because V1/V2 were applied
to Supabase manually while Flyway was off. First prod boot baselined at V2 (no
re-run of V1/V2) and applied only V3.

**5. `redirectURI` = the current page origin.** In popup/`web_message` mode Apple
posts the token back to the redirect_uri's origin, so a hardcoded host throws
"Unable to post message" when the page is a different host. The frontend uses
`window.location.origin + "/"`; every served origin must be a registered Apple
Return URL.

**6. Canonical domain = `irontrainer.app`.** The 3-host setup (apex, `www`,
`iron-trainer.up.railway.app`) with host-scoped cookies let a user be "logged in"
on one host and anonymous on another — which made linking create orphan accounts.
`CanonicalHostRoute` (Vert.x route, order -1000, gated on `CANONICAL_HOST`) 301s
`www.<host>` → apex on every path (the Railway health-check host is left alone).
`CORS_ORIGINS` / `STRAVA_REDIRECT_URI` / Apple Return URL / Strava callback domain
all moved to the apex → one origin, one cookie, one Return URL.

## Consequences

- Athletes can create/enter an account with Apple, no Strava required; a logged-in
  athlete can link Apple to sign in later on another device.
- Native-image build needed the **Tink** dependency (`com.google.crypto.tink:tink`)
  that nimbus-jose-jwt references for unused algorithms — GraalVM (behind Railway's
  floating Mandrel builder tag) hard-fails on the missing class while CI's
  container-build tolerated it (bean 42u0: pin the builder image).
- Apple web config is exact-match and finicky: Return URL must match the sent
  `redirect_uri` **byte-for-byte including the trailing slash** (`https://irontrainer.app/`),
  and registration must be saved through Continue → Save. "Sign Up Not Completed"
  after the sheet opens = Return URL mismatch; "Unable to post message" = origin
  mismatch.
- **iOS in-app SIWA is deferred** (device pairing already authenticates the app;
  4.8 likely N/A). Follow-up bean tracks it.
- Reverse link (attach Strava to an Apple-first account) and merging two
  pre-existing accounts are out of scope (bean 4uj1).

## Alternatives considered

- **Supabase Auth / a managed identity provider** — rejected for now: it would run a
  second identity system in parallel with the existing Strava+session model (bean
  p15n researches a future consolidation).
- **Cloudflare edge redirect rule** for `www` → apex — the Cloudflare MCP token is
  DNS-only (can't create redirect rules), so the redirect is app-level instead.
- **Hardcoded `redirectURI`** — broke across the apex/www split; the page-origin
  approach works on any registered origin.
