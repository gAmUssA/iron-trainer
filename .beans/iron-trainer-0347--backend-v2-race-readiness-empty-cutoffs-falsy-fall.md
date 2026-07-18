---
# iron-trainer-0347
title: 'backend-v2: race_readiness empty-cutoffs falsy fallback (PR #60 review)'
status: completed
type: bug
created_at: 2026-07-18T03:07:28Z
updated_at: 2026-07-18T03:07:28Z
parent: iron-trainer-eom4
---

Retroactive review of merged PR #60 found: cutoffChecks used 'cutoffs != null' where Python uses 'cutoffs or {default}'. An empty (non-null) cutoffs map kept the empty map → c.get('swim') null → NPE unboxing into int limit (500) instead of the default-cutoff payload. Latent (only caller passes a full 3-key map) but a real parity divergence.

## Summary of Changes
Guard on (cutoffs != null && !isEmpty()) → falsy-map fallback. +1 unit test exercising the empty-cutoffs branch (defaults to 70*60 Swim). RaceReadinessTest 2/2.
