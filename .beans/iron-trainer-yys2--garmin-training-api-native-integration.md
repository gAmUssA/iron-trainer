---
# iron-trainer-yys2
title: Garmin Training API native integration
status: todo
type: feature
priority: normal
created_at: 2026-07-29T12:54:22Z
updated_at: 2026-07-29T12:54:22Z
parent: iron-trainer-hkbl
blocked_by:
    - iron-trainer-hkbl
---

Native push to Garmin Connect via the Training API. BLOCKED: Garmin Developer Program paused for new applicants (no form/ETA as of 2026-07). Build once approved.

## Todo (once access granted)
- [ ] OAuth 2.0 + PKCE connect flow (web + iOS): authorizeUser → code → token; store per-user tokens; handle consent + revocation.
- [ ] Workout JSON mapper: our structured-workout model → Garmin workout/trainingPlan JSON (sportType, steps warmup/interval/recovery/rest/cooldown, HR/power/pace/cadence targets, repeat blocks).
- [ ] Schedule onto the Connect calendar by date (trainingPlan resource).
- [ ] Ping/webhook handler for confirmations.
- [ ] Follow Garmin branding guidelines; honor throttled quota + PII/consent terms.
Effort: ~2-4 wk backend + 1-4 wk Garmin integration. See parent epic for the full research.
