---
# iron-trainer-pql8
title: HealthKitAuthorizer + entitlements + Settings 'Connect Apple Health' card
status: todo
type: task
created_at: 2026-07-21T15:41:54Z
updated_at: 2026-07-21T15:41:54Z
parent: iron-trainer-yrsz
---

Entitlements (healthkit + healthkit.background-delivery, NOT clinical records) + NSHealthShareUsageDescription. HealthKitAuthorizer requests read access in-context. Settings 'Connect Apple Health' card with last-sync + per-metric freshness. NEVER gate on authorizationStatus (read-denial is invisible) — empty states point user to Health → Sharing. Privacy policy covering health data (Guideline 5.1.3) + App Privacy label (Health & Fitness, linked to identity, no tracking).
