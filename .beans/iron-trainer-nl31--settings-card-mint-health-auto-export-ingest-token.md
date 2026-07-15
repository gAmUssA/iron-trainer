---
# iron-trainer-nl31
title: 'Settings card: mint Health Auto Export ingest token + setup guide'
status: completed
type: task
priority: normal
created_at: 2026-07-15T03:14:05Z
updated_at: 2026-07-15T03:22:19Z
parent: iron-trainer-udbc
---

Web Setup card that mints a dedicated bearer token for the Health Auto Export automation and shows copy-paste setup (URL, header, app settings). Follow-up to clye/PR #27.

## Summary of Changes

POST /api/device/ingest-token (session-authenticated, mints DeviceToken named health-auto-export, plaintext returned once, hash stored; revocable via existing revoke-all). Web HealthIngestCard on Settings tab: one button mints the token and shows exact Health Auto Export setup (URL, Authorization header with copy button, metric/aggregation checklist). Test: minted token authenticates a real ingest push. ADR 0019 updated.

Also on iOS: Settings → Health data section — mint token (copy header/URL to clipboard, same-device paste into Health Auto Export) + 'Last push: date — sleep/HRV/RHR' status line via /api/health/recovery, the is-it-actually-pushing check on the very device that pushes.
