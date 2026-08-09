# 0061 — De-Python: compact JSON (retire PyJson byte-parity) (2026-08-09)

- **Status:** Accepted
- **Bean:** ex4m (epic y2yz — de-Python backend-v2)

## Context

`PyJson.dumps` reproduced Python `json.dumps`' default spacing (`", "` / `": "`)
byte-for-byte, because the JSON blobs backend-v2 writes to the shared DB
(`result_json`, `inputs_json`, `structure_json`, `weeks_json`, `story_json`,
`readiness_json`, the session-cookie payload) were formerly written *and read* by a
FastAPI backend too (ADR 0020, strangler). FastAPI is decommissioned — backend-v2 is
the sole reader/writer of these blobs and only ever **parses** them. The whitespace
is invisible to a parser, so the byte-parity printer is dead weight.

## Decision

- `PyJson.dumps` now emits plain **compact** JSON via the injected `ObjectMapper`
  (`{"a":1}`); the custom `MinimalPrettyPrinter` subclass and its imports are
  deleted. All ~15 call sites are unchanged — they get compact output for free.
- **Timestamps unchanged.** `utcNowIso()` / `utcIsoDaysAgo()` keep the exact
  `.SSSSSS+00:00` ISO format — it is the iOS wire contract and the string columns
  still use lexicographic range queries (that migration is a separate deferred bean,
  x78x).

## Cookie safety (the one live-data touch)

`SessionCookie.sign` uses `PyJson.dumps` for the cookie payload, so new cookies now
carry a compact payload. This is safe:
- **Verification reads the received bytes** and never re-serializes, so existing
  browser cookies signed by FastAPI (spaced payload, 14-day max-age) still verify —
  proven by `SessionCookieTest.verifiesPythonSignedCookie`, which keeps passing
  against a real Python-signed fixture.
- backend-v2 signs *and* verifies its own cookies → self-consistent.
- No external verifier exists anymore (iOS uses bearer tokens, not this cookie).

Retiring the itsdangerous scheme itself is out of scope (deferred — bean x8t8).

## Consequences

- New DB blobs and cookies are compact; old rows/cookies keep working (read path is
  format-agnostic). No migration needed.
- The obsolete `SessionCookieTest.mintsByteIdenticalToPython` (asserted mint ==
  Python bytes) is removed; `PyJsonTest` and `JobRunnerTest` now assert compact.

## Verification

`PyJsonTest` (compact) + `SessionCookieTest` (10 tests incl. Python-signed-cookie
verify) pass locally without Docker; full module test-compiles; `JobRunnerTest`
`result_json` compact assertion runs in CI. Local multi-agent review + Copilot before
merge.
