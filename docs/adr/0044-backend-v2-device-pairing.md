# 0044 — Backend v2: device pairing (native-app token minting) (2026-07-20)

Date: 2026-07-20
Epic: iron-trainer-eom4 (Phase 7) · Pattern: ADR 0020 / 0037 (security slice)

## Context

The **last** FastAPI-only vertical, held for a focused review because it MINTS
bearer tokens — the native-app login. backend-v2 already *verified* device tokens
(`BearerAuthFilter`) but couldn't issue them. Port of auth_router's device
endpoints + repo's token/pairing functions. With this, every FastAPI endpoint is
on backend-v2.

## What was built

- **`DeviceToken` entity expanded** to the pairing columns (`pairing_code`,
  `pairing_expires_at`, `created_at`, `last_used_at`).
- **`Devices` bean** — the token primitives, plaintext returned once, only the
  SHA-256 hash stored:
  - `createPairingCode` — an 8-hex code (`token_hex(4)`), 10-min TTL → `{code,
    expires_at, expires_in}`.
  - `createBearerToken` — `token_urlsafe(32)` (fresh `SecureRandom` per call:
    native-image heap rejects a static one).
  - `claimPairingCode` — a valid, unexpired, **unclaimed** code → `{token,
    athlete{name, strava_athlete_id}}`; null on unknown/claimed/expired;
    `device_name` overrides the name.
  - `bearerTokenName` / `revokeTokens`.
- **`ClaimThrottle` bean** — the unauthenticated `/claim`'s brute-force friction
  (10 failures / 60s per client, keyed on the first X-Forwarded-For hop or the
  socket host; `synchronized` because Quarkus is multi-threaded).
- **`DeviceResource`** (`@Path("/api/device")`):
  - `POST /pairing-code` (auth; optional `{name}`).
  - `POST /ingest-token` (auth; a Health-Auto-Export bearer — an **ingest token
    can't mint siblings** → 403).
  - `POST /claim` (unauthenticated; throttled; required `code` → 422; invalid →
    400; success clears the client's failures).
  - `DELETE /tokens` (auth; revoke all → `{revoked}`).

## Testing

- **`DevicePairingTest`** (`@QuarkusTest`): pair → claim → the token is stored/
  hashed (device_name override); invalid → 400, missing code → 422; revoke count;
  ingest-token mints + can't-mint-siblings 403; the throttle 429s after 10 fails.
- **Parity** (`test_device_pairing_parity`): **cross-backend** — v1 mints a code,
  v2 claims it (shared `device_token` row), and the minted token authenticates on
  BOTH backends (shared `token_hash`); response shapes + 600s TTL + invalid-code
  400 (tokens are random, so bytes can't match). Verified vs real backends.
- Full v2 suite: 191 green.

## Phase 7

**This completes the port — every FastAPI endpoint is now on backend-v2.** The
remaining Phase-7 work (bean foi1) is the operational front-door cutover +
FastAPI decommission, not code.

## Code-review fixes (applied before merge)

Security-focused review — 1 PLAUSIBLE (concurrency) + 4 CONFIRMED + 1 cleanup:
1. **Concurrent-claim race** — `claimPairingCode` now takes a
   `PESSIMISTIC_WRITE` lock (SELECT … FOR UPDATE) so two racing claims of the same
   code serialize; the second sees `token_hash != null` → rejected. Without it,
   both could mint tokens (last-writer-wins → one device permanently 401s).
2. **Non-string `device_name`** → 422 (was silently dropped); same for
   pairing-code `name`.
3. **Throttle order** — the body is validated (422) BEFORE the throttle check
   (429), matching FastAPI's pydantic-then-endpoint order.
4. **Non-object / malformed claim body** → 422 (parse the raw body + require an
   object), not a Jackson 400.
5. **X-Forwarded-For whitespace** — `!isEmpty` (Python truthiness), not `!isBlank`.
6. **Token idiom de-duplicated** into `util.SecureTokens` (`urlsafe`/`hex`);
   `StravaOAuth.newState` reuses it too.

v2 suite 191 green; device + strava parity re-verified vs real backends.
