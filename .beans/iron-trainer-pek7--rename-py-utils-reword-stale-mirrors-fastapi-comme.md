---
# iron-trainer-pek7
title: Rename Py* utils + reword stale 'mirrors FastAPI' comments
status: completed
type: task
priority: low
created_at: 2026-08-08T23:24:17Z
updated_at: 2026-08-09T16:10:03Z
parent: iron-trainer-y2yz
---

Cosmetic debt cleanup, no behavior change.

## Todo
- [ ] Rename PyJson→Json (and/or Times for the ISO helpers), Py→Round/Fmt. KEEP utcNowIso's exact format (yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx) — bytes are coupled to string-timestamp columns and the iOS wire.
- [ ] Sweep the ~120 'port of / mirrors FastAPI / parity' comments — delete or reword to state the CURRENT contract. Opportunistic (as files are touched), not one giant churn PR.
- [ ] Add a ponytail note on JobRunner.submitLock: single-instance ceiling; per-(athlete,kind) DB advisory lock if it ever scales out.

## Notes
LOW risk / low value — do while touching files, or as one small mechanical PR if desired.

## Summary of Changes
Reworded the actively-misleading byte-parity/'both backends'/shared-DB comments (PyJson.loads, PlanResource.parseJson, FitnessTestsResource, Metrics, HrZones, FitnessTests) to the real rationale; +ponytail ceiling note on JobRunner.submitLock. DEFERRED (deliberately, per research): the PyJson/Py class renames (cosmetic, 150+ Py sites, golden-test drift risk) and the full sweep of harmless 'matches FastAPI' provenance comments (opportunistic, not a churn PR). ADR 0062.
