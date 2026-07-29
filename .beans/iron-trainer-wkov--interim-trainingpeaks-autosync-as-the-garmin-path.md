---
# iron-trainer-wkov
title: 'Interim: TrainingPeaks AutoSync as the Garmin path'
status: todo
type: feature
priority: normal
created_at: 2026-07-29T12:54:22Z
updated_at: 2026-07-29T12:54:22Z
parent: iron-trainer-hkbl
---

Ship the Garmin path NOW via TrainingPeaks (we already export to TP). TP's Garmin Connect AutoSync pushes future structured workouts (rolling ~15 days) to Garmin Connect → device. Zero Garmin approval.

## Todo
- [ ] Verify our TP export publishes FUTURE/planned workouts to the TP calendar (AutoSync only pushes upcoming, not completed).
- [ ] Confirm sport-type mapping (Run/Bike/Swim/Custom map; Strength/brick/day-off do NOT).
- [ ] Document the one-time user step: link TrainingPeaks ↔ Garmin Connect (TP AutoSync).
- [ ] Surface it in-app (Settings/help): 'Get workouts on your Garmin via TrainingPeaks'.

Limits: rolling next-15-days only; fidelity inherited from TP mapping.
