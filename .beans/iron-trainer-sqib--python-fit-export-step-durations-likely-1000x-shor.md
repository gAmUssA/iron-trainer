---
# iron-trainer-sqib
title: 'Python FIT export: step durations likely 1000x short on real devices (scale bug)'
status: completed
type: bug
priority: high
created_at: 2026-07-15T19:16:07Z
updated_at: 2026-07-21T21:21:17Z
---

Found by the backend-v2 cross-impl FIT test: fit_export.py sets duration_time=seconds with a comment claiming fit-tool applies the x1000 ms scale — but the official Garmin Java SDK decodes our files as 0.6s steps (raw 600, profile scale 1000). If a Garmin device imports our .fit, every step may last under a second. Never device-verified (Watch uses .itw, Zwift uses ZWO). Verify by importing a generated .fit into Garmin Connect/device; if confirmed, fix is duration_time*1000 (or set raw duration_value in ms) + regenerate the Java test reference to assert 600000. Evidence: backend-v2/src/test/java/io/gamov/irontrainer/FitInteropTest.java

## Resolved (2026-07-21) — live path was already correct
Investigation: the 1000x bug is in the PYTHON fit_export.py (frozen/decommissioned). backend-v2 FitExport.java already encodes durations FIT-spec-correct: m.setDurationTime((float)dur) uses the SDK's SCALED setter (seconds in → ms raw out), so a 600s step decodes as 600000 (device reads 600s). FitInteropTest's assertEquals(600,...) decodes a committed PYTHON golden file (reference_bike_py.fit), documenting the Python bug — NOT backend-v2 output. Added FitDurationScaleTest: exports via backend-v2 FitExport, decodes with the Garmin SDK, asserts durationValue==600000 (regression guard). No code fix needed in the live path; Python path is dead. Optional: Viktor device-verify a real .fit import, but backend-v2 is spec-correct. FitDurationScaleTest 1/0.
