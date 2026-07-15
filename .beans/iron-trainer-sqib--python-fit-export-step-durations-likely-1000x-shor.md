---
# iron-trainer-sqib
title: 'Python FIT export: step durations likely 1000x short on real devices (scale bug)'
status: todo
type: bug
priority: high
created_at: 2026-07-15T19:16:07Z
updated_at: 2026-07-15T19:16:07Z
---

Found by the backend-v2 cross-impl FIT test: fit_export.py sets duration_time=seconds with a comment claiming fit-tool applies the x1000 ms scale — but the official Garmin Java SDK decodes our files as 0.6s steps (raw 600, profile scale 1000). If a Garmin device imports our .fit, every step may last under a second. Never device-verified (Watch uses .itw, Zwift uses ZWO). Verify by importing a generated .fit into Garmin Connect/device; if confirmed, fix is duration_time*1000 (or set raw duration_value in ms) + regenerate the Java test reference to assert 600000. Evidence: backend-v2/src/test/java/io/gamov/irontrainer/FitInteropTest.java
