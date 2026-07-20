---
# iron-trainer-3qcz
title: 'backend-v2: port device pairing (pairing-code, claim, ingest-token, revoke tokens) — native-app login'
status: completed
type: feature
priority: high
created_at: 2026-07-20T17:41:50Z
updated_at: 2026-07-20T17:51:01Z
---

Port the native-app bearer-token minting: POST /api/device/pairing-code, POST /api/device/claim, POST /api/device/ingest-token, DELETE /api/device/tokens. Security slice (final Phase-7 unit). backend-v2 verifies tokens (BearerAuthFilter) but doesn't mint them.

## Shipped
DeviceToken entity expanded; Devices bean (createPairingCode/createBearerToken/claimPairingCode/bearerTokenName/revokeTokens, SecureRandom token_hex/token_urlsafe, sha256 hash); ClaimThrottle (10/60s per client); DeviceResource (pairing-code/ingest-token[403 sibling]/claim[throttled,422/400]/tokens). Tests: DevicePairingTest + cross-backend pairing parity. v2 191 green. ADR 0044. **Every FastAPI endpoint now ported — Phase-7 code gate CLEAR.**
