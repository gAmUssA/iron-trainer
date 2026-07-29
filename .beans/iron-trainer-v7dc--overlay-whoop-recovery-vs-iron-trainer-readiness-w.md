---
# iron-trainer-v7dc
title: 'Overlay: WHOOP Recovery vs Iron Trainer Readiness (web + iOS)'
status: todo
type: feature
priority: normal
created_at: 2026-07-29T20:38:26Z
updated_at: 2026-07-29T20:38:26Z
parent: iron-trainer-ids6
blocked_by:
    - iron-trainer-ids6
---

Present two INDEPENDENT, labeled recovery signals side by side: 'Iron Trainer Readiness' (our HealthKit-derived score) and 'WHOOP Recovery' (proprietary %, from the WHOOP API). Overlay/compare, no merging.

## Todo
- [ ] Recovery view / widget: show both scores + a compare/trend overlay (two lines).
- [ ] Label sources clearly; handle 'WHOOP not connected' / 'HealthKit sync off' gracefully.
- [ ] Optional: surface divergence (e.g., WHOOP says recovered, our HRV-based says not).
Depends on the WHOOP API pull feature.
