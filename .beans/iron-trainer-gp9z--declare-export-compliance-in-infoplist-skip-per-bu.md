---
# iron-trainer-gp9z
title: Declare export compliance in Info.plist (skip per-build questionnaire)
status: completed
type: task
priority: normal
created_at: 2026-07-13T20:11:02Z
updated_at: 2026-07-13T20:11:41Z
---

TestFlight asks the encryption questionnaire on every build. App uses only exempt encryption (HTTPS/ATS, Keychain) → ITSAppUsesNonExemptEncryption=false in both app and widget Info.plists.

## Summary of Changes

ITSAppUsesNonExemptEncryption=false added to app + widget Info.plists (exempt-only encryption: HTTPS/ATS + Keychain). Both targets build. Takes effect from the next uploaded build.
