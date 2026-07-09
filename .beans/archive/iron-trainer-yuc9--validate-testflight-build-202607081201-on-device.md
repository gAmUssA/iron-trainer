---
# iron-trainer-yuc9
title: Validate TestFlight build 202607081201 on device
status: scrapped
type: task
priority: normal
created_at: 2026-07-08T17:29:56Z
updated_at: 2026-07-09T14:43:14Z
---

Build 0.1.0 (202607081201) uploaded 2026-07-08 with PRs #10 + #13.
- [ ] Sign out → token revoked server-side → re-pair required
- [ ] Expired/garbage pairing code shows 'Pairing failed' alert
- [ ] Pairing QR for a different server → 'Switch server?' confirmation
- [ ] Late-night 'today' scheduling lands at a valid Watch time

## Reasons for Scrapping

Superseded: build 202607081201 was replaced by 1335/1816/2231 within a day. Its checklist items were validated along the way — pairing flow + server-switch confirmation exercised live in simulator (PR #14/#15 testing), back-nav + widgets confirmed on device by Viktor, late-night scheduling clamp shipped and covered by the PR #13/#15 fixes. Sign-out token revocation is verified server-side by test_hardening; any residual on-device check happens naturally in daily use.
