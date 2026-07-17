# 0022 — Backend v2: session-cookie verification (Phase 7 keystone, 2026-07-17)

Date: 2026-07-17
Status: Accepted
Epic: iron-trainer-eom4 (Phase 7) · Feature: iron-trainer-4quc · Extends: ADR 0020 §3

## Context

ADR 0020 §3 established the auth seam: backend-v2 resolves **bearer** device
tokens (SHA-256 → shared `device_token` table) via `BearerAuthFilter` +
`CurrentAthlete`, and deliberately left **session cookies FastAPI-side** — which
capped early strangling to bearer-only, GET-only traffic (readiness, exports,
nutrition/tests reads). Every ported **write** endpoint (fitness-tests,
nutrition, metrics writes) and the Strava sync sit dormant behind the strangler
because web (browser) clients authenticate with a signed cookie backend-v2
couldn't read, and the strangler proxy is bearer-only.

This is the keystone that unblocks the write flips: teach backend-v2 to
authenticate a web request.

## Decisions

1. **Verify, don't mint.** backend-v2 only *verifies* the Starlette `session`
   cookie; minting stays on the FastAPI Strava OAuth callback
   (`request.session["athlete_id"] = ...`) for the whole strangle window.
   Rationale: existing browser sessions keep working with **no re-login and no
   format change**, and the crypto surface on the Quarkus side stays
   verify-only (no secret-bearing token issuance to get wrong).

2. **Byte-exact itsdangerous parity, not a shared-JWT switch.** The bean offered
   either. A JWT switch would force FastAPI to change its cookie format,
   invalidating every live session and widening the blast radius. Instead
   backend-v2 reproduces Starlette's `SessionMiddleware` (itsdangerous 2.x
   `TimestampSigner`) exactly:
   - `key = SHA1("itsdangerous.Signer" + "signer" + SESSION_SECRET)` (django-concat, 20 bytes)
   - cookie = `payload "." timestamp "." signature`
   - `payload` = **standard** base64 of `json.dumps(session)` (with `=` padding)
   - `timestamp` = urlsafe-nopad base64 of the minimal big-endian Unix seconds
   - `signature` = urlsafe-nopad base64 of `HMAC-SHA1(key, payload "." timestamp)`, constant-time compared
   - age gate `0 ≤ now − ts ≤ 1209600` (Starlette's 14-day `max_age`)
   Verified against a cookie signed by the **real Python itsdangerous 2.2.0**
   (pinned-timestamp golden fixture) so the test is a true cross-implementation
   parity check, not a self-consistency check.

3. **Shared secret via `SESSION_SECRET`.** `irontrainer.session-secret =
   ${SESSION_SECRET:}` — the same env var FastAPI signs with. Read at request
   time through `ConfigProvider` (not field-injected), matching the existing
   `auth-required` handling to survive native-image static-init baking. An empty
   secret makes the verifier a no-op (returns null) rather than throwing.

4. **Precedence unchanged: Bearer wins over cookie.** The filter tries bearer
   first (as before), then the `session` cookie, then — only when
   `auth-required=false` — the default athlete (id 1). Mirrors FastAPI's
   `_athlete_id_from_bearer` → session → default order exactly.

5. **Manual Cookie-header parse.** The itsdangerous value contains `=` (base64
   padding) and `.` separators, which JAX-RS cookie parsing mishandles
   (splits on `=`). `BearerAuthFilter.sessionCookieValue` extracts the raw
   `session=` value up to the first `;` by hand.

## Consequences

- backend-v2 can now authenticate web clients → the write flips (bean hy6c) and
  Strava-sync activation are unblocked; the remaining gate is the strangler
  proxy (currently GET-only, bearer-only, cookie traffic served locally), which
  must learn to forward POST + cookies.
- `SESSION_SECRET` must be identical on both backends in prod (it already is —
  single Railway env). If it ever diverges, web requests silently fall through
  to FastAPI (proxy) / the default athlete — fail-closed, not fail-open.
- No FastAPI change; no session invalidation; no new dependency (JDK crypto +
  the Jackson already on the classpath).

## Not done here (captured as beans under eom4)

- **hy6c** — strangler forwards POST + cookies; flip the first write endpoint.
- **9ndj** — port remaining routers (dashboards/insights/races).
- **qbol** — Quinoa frontend serving.
- **j8zk** — cutover: decommission FastAPI, freeze Alembic.

## Verification

11 new backend-v2 tests: byte-exact parity vs Python-signed cookie, age-gate
(valid across the full window, expired, future-dated), tamper + wrong-secret
rejection, header-parse robustness (incl. duplicate-cookie last-wins), and an
end-to-end `@QuarkusTest` proving a cookie-only request resolves the athlete and
that a Bearer token still wins over a cookie. Full suite 85/85 green in a clean
(no-creds) env.

## Review hardening (high-effort multi-agent review, pre-merge)

The review confirmed the core crypto (HMAC, constant-time compare, key
derivation) and drove five fixes before merge:

1. **Boot guard** — refuse to start when `auth-required` and no `SESSION_SECRET`
   (parity with FastAPI's `enforce_secure_config`); without it, cookie verify is
   a silent request-time no-op.
2. **Cookie parse: last wins** — a duplicate `session=` now resolves to the same
   athlete as Python `http.cookies` (was first-match → wrong-athlete risk).
3. **Configurable default athlete** — `irontrainer.default-athlete-id`
   (`DEFAULT_ATHLETE_ID`, default 1) instead of a hardcoded 1, matching
   `settings.default_athlete_id`.
4. **Dropped redundant base64 padding** (Java's URL decoder accepts unpadded).
5. Two candidates were verified as *faithful* parity and kept: `age < 0`
   rejection matches itsdangerous 2.x `SignatureExpired` (empirically checked),
   and the `>8`-byte timestamp guard is unreachable post-signature. Test-crypto
   duplication + per-request config reads deferred (cleanup bean).
