---
# iron-trainer-w5w0
title: 'backend-v2: port export ZIP bundles (plan.zip + week/{week_start}.zip)'
status: completed
type: feature
priority: high
created_at: 2026-07-20T17:07:02Z
updated_at: 2026-07-20T17:15:59Z
---

Port GET /api/export/plan.zip + GET /api/export/week/{week_start}.zip: bundle_zip(workouts) → zip of per-workout files. Reuse the existing v2 per-workout export. Phase-7 tail (last non-security unit).

## Shipped
GET /api/export/plan.zip + /week/{week_start}.zip: bundleZip (per workout .fit/.zwo(bike)/.itw + README) reusing existing builders; filename(w,ext)+slug port; README resource (native-registered). 404 no-plan, 500 bad date. Parity: entry-name set + README bytes + valid entries (zip/.fit/.zwo bytes intentionally differ). v2 186 green. ADR 0043. Phase-7 backlog: 6 → 4 (device pairing only).
