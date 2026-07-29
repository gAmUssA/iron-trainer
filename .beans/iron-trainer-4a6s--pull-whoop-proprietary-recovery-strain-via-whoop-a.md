---
# iron-trainer-4a6s
title: Pull WHOOP proprietary Recovery % + Strain via WHOOP API (distinct source)
status: todo
type: feature
priority: normal
created_at: 2026-07-29T20:38:25Z
updated_at: 2026-07-29T20:38:25Z
parent: iron-trainer-ids6
---

The ONLY thing worth pulling directly from WHOOP: its proprietary Recovery % (+ Strain) — NOT in Apple Health. Store as a distinct metric, source='whoop_api'. Do NOT pull raw HRV/RHR/sleep from WHOOP (already ingested via HealthKit — would double-count).

## Todo
- [ ] WHOOP dev app: create at id.whoop.com → Client ID/Secret (self-serve; ≤10 users until approval — fine for personal/beta).
- [ ] OAuth 2.0 Authorization Code + offline scope: iOS ASWebAuthenticationSession (or web redirect) → backend (Quarkus) holds secret, code→token exchange, store + ROTATE refresh tokens (single-use). Scopes: read:recovery, read:cycles.
- [ ] Pull layer: GET recovery (via Cycle endpoints) — recovery_score, day strain; cursor pagination (nextToken, start/end). Respect 100/min + 10k/day.
- [ ] Store as whoop_recovery / whoop_strain with source='whoop_api'; reuse daily_recovery-style schema + a source column.
- [ ] (Later) webhooks (recovery.updated, HMAC-SHA256 verify, fetch-on-notify) — start with a daily/hourly poll; add webhooks only if latency matters.
Effort ~3-5 days. Auth token: ~1h access, rotating refresh.
