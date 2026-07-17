# 0023 — Strangler write forwarding (POST/cookies) with asymmetric fallback (2026-07-17)

Date: 2026-07-17
Status: Accepted
Epic: iron-trainer-eom4 (Phase 7) · Feature: iron-trainer-gb30 · Extends: ADR 0020, ADR 0022

## Context

Every ported write endpoint (fitness-tests, nutrition, metrics writes) sat
dormant because the strangler proxy was **GET-only, bearer-only**, and served
all cookie/web traffic locally. ADR 0022 taught backend-v2 to verify session
cookies; this teaches the proxy to actually deliver writes (and the cookie) to
it — turning that dormant, parity-tested code into flippable capability.

## Decisions

1. **Separate write allowlist `PROXY_WRITE_PATHS`.** Writes proxy on their own
   env-driven allowlist, independent of the read allowlist `PROXY_PATHS`. A
   vertical's reads can flip without its writes (and vice-versa), and deploying
   this change moves **zero** traffic until a path is added — the same
   empty-guarded, instant-rollback discipline as reads.

2. **Writes forward bearer OR session cookie; reads unchanged.** A write is
   proxy-eligible with either credential, and both `Authorization` and `Cookie`
   headers are forwarded so backend-v2 re-authenticates (bearer for iOS, cookie
   for web — ADR 0022). Reads stay **bearer-only** deliberately: enabling cookie
   reads would silently start serving the already-live prod read-flips
   (readiness, nutrition, tests) from backend-v2 for web users with no config
   flip. Cookie reads, if wanted, are a separate, explicit change.

3. **Asymmetric fallback — the core decision.** Reads are idempotent, so the
   existing "unreachable OR 5xx → serve locally" guarantee holds. Writes are
   **not** idempotent (recording a test result twice = a duplicate row), so:
   - **Unreachable backend (connect error / connect timeout — request never
     delivered):** fall back locally, replaying the buffered request body.
     Nothing was committed, so this is safe and preserves rollback.
   - **Any post-send failure (5xx response, read timeout, protocol error — the
     request WAS delivered):** do **not** retry locally. backend-v2 may have
     already committed; a local retry would double-apply the mutation. A 5xx is
     forwarded to the client as-is; a post-send transport error returns 502.
   This trades the reads' "a v2 failure never breaks yesterday's client"
   guarantee for correctness: for a non-idempotent write, surfacing an error the
   client can decide to retry beats silently applying it twice.

4. **Body buffering only for proxied writes.** The middleware stays pure-ASGI
   and never touches a body it isn't forwarding: read bodies and non-proxied
   write bodies pass through untouched. A proxied write's body is drained into a
   buffer (to forward, or to replay on the unreachable-backend fallback via a
   one-shot replay `receive`).

## Consequences

- The write flip is a pure Railway env change (`PROXY_WRITE_PATHS`), instantly
  reversible — same operational model as the read flips. **Moving live mutation
  traffic to Quarkus is the first of its kind, so the actual prod flip is a
  deliberate, separately-confirmed step, not part of this PR.**
- A backend-v2 write bug now surfaces to the client (5xx/502) instead of being
  masked by a local retry. That is the correct failure mode for mutations, but
  it means write flips carry more risk than read flips — flip one endpoint at a
  time and watch it.
- `fetch` (GET) keeps its `(url, authz)` signature; writes use a new
  `fetch_write(url, method, body, headers)`. Existing stubs/tests unchanged.

## Verification

14 strangler tests (8 existing + 6 new): bearer write proxies (method/body/auth
forwarded), cookie write proxies (Cookie forwarded, no bearer), unauthenticated
write stays local, write-not-in-write-allowlist stays local, 5xx forwarded (not
retried — proven by an empty body that would 422 locally yet returns the proxied
500), connect-error falls back locally (replayed body → local 422), read-timeout
returns 502 (no fallback). Full backend suite 215/215 green. No backend-v2 or
parity-harness change.

## Review hardening (high-effort multi-agent review, pre-merge)

The review confirmed the asymmetric-fallback design and drove four fixes:

1. **Truncated-body forward** — `_read_body` now raises `ClientDisconnect` on a
   mid-upload disconnect instead of forwarding the partial buffer as a complete
   body (which could commit a partial mutation).
2. **Cookie over-match** — eligibility uses the exact cookie name
   (`"session" in request.cookies`), not the `"session=" in cookie` substring
   that matched `websession=`/`mysession=` and could flip an unauthenticated
   write.
3. **`PoolTimeout` fallback** — added to the never-delivered set
   (`_UNDELIVERED_ERRORS`), so a saturated connection pool falls back locally
   (safe, nothing committed) instead of returning 502.
4. **Header drop** — writes now forward all request headers except
   hop-by-hop/host/content-length, so `Content-Encoding`, `Idempotency-Key`,
   etc. reach backend-v2 (was a 3-header allowlist).

The delivered-but-lost-response 502 remains a documented limitation: a client
auto-retrying on 5xx can still double-apply. Fully closing it needs idempotency
keys end-to-end (bean idmp) — the strangler now forwards `Idempotency-Key`, so
that follow-up only needs backend-v2 dedupe.

## Not done here (bean gb30 remainder + siblings)

- The actual prod flip of `POST /api/tests/result` (+ `/apply`, `/schedule`) via
  Railway `PROXY_WRITE_PATHS` — a confirmed follow-up.
- Idempotency-key dedupe on backend-v2 (bean idmp).
- Cookie reads (if ever wanted) — separate change, see decision 2.
