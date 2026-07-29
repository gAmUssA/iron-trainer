---
# iron-trainer-hkbl
title: Garmin Connect integration — push structured workouts to devices
status: todo
type: epic
priority: normal
created_at: 2026-07-29T12:53:30Z
updated_at: 2026-07-29T20:33:42Z
---

Goal: push generated structured swim/bike/run workouts (and plans) from Iron Trainer to the athlete's Garmin Connect calendar → their Garmin device, so they follow the workout on-watch. Complements the existing TrainingPeaks export.

## Research (2026-07-29)
**Right API:** the **Garmin Training API** (part of the Garmin Connect Developer Program). It publishes workouts + training plans to the Connect calendar; Garmin then syncs them to compatible devices. (Activity API = pull completed activities, wrong direction; Health API = wellness metrics; Courses API = GPS routes.)

**⚠ BLOCKER — program PAUSED for new applicants (2026):** Garmin has 'temporarily paused the review and approval of new API access requests' while 'modernizing' the program. The application form is REMOVED, there is no waitlist and no reopening ETA. Existing integrations keep working. **So we cannot apply right now.** (Connect IQ is separate and still open, but it's the wrong tool — on-device apps, not calendar workout publishing.)

**Access model (when open):** business-use only, apply + approve, sign the Garmin Connect Developer Program Agreement, **no fees for Training**, status confirmed in ~2 business days, integration ~1-4 weeks. No separate sandbox — throttled production is the eval env.

**Auth:** OAuth 2.0 + PKCE. Per-app client key/secret (issued at approval). User authorizes at apis.garmin.com/tools/oauth2/authorizeUser → code → token at diauth.garmin.com. Per-user consent; users revoke at connect.garmin.com/modern/settings.

**Workout format:** REST + JSON (NOT FIT upload) — a 'workout' resource + a 'trainingPlan' (schedules onto the Connect calendar by date). Steps: warmup/interval/recovery/rest/cooldown; duration by time/distance/lap; targets for HR / power (incl. % threshold) / pace / cadence; nested repeat blocks. Garmin converts to on-device FIT. Run/Bike/Swim map cleanly; **Strength & brick workouts do NOT** map to structured device workouts. Exact step/plan caps + rate limits are behind post-approval docs.

**Constraints:** throttled per-app quota; ping/webhook notifications for confirmations; Garmin branding guidelines; business-use + consent/PII terms in the agreement.

## Does Viktor need to request API access?
**YES — but cannot right now** (program paused, no form). When it reopens: apply at developer.garmin.com/gc-developer-program/overview/ for the **Training API**; Garmin asks for a business entity, the app + use case, which APIs, expected user volume, and a technical contact. Meanwhile: periodically re-check developer.garmin.com.

## RECOMMENDATION (lazy-correct)
**Interim: route via TrainingPeaks AutoSync** — we already export to TrainingPeaks; TP's Garmin Connect AutoSync pushes FUTURE structured workouts (rolling next ~15 days) from the TP calendar → Garmin Connect → device. Zero Garmin approval, works today, covers the triathlon core (Run/Bike/Swim). User links TP↔Garmin once. Then build the native Training API integration when the program reopens (~2-4 wk backend + 1-4 wk integration).

## Sources
- Training API: https://developer.garmin.com/gc-developer-program/training-api/
- Program overview/FAQ: https://developer.garmin.com/gc-developer-program/overview/ , /program-faq/
- Program paused: https://www.themomentum.ai/blog/garmin-developer-program-closed-roadmap
- OAuth2 PKCE spec: https://developerportal.garmin.com/sites/default/files/OAuth2PKCE_1.pdf
- TrainingPeaks Garmin AutoSync: https://www.trainingpeaks.com/coach-blog/garmin-connect-autosync-integration/ , https://help.trainingpeaks.com/hc/en-us/articles/204070864
- Workout JSON reference: https://github.com/ThomasRondof/GarminWorkoutAItoJSON

## Update — Garmin acquired TrainingPeaks (per Viktor, 2026)
Garmin now OWNS TrainingPeaks. This likely explains the Developer Program pause (consolidating the training-data pipeline) and makes the **interim TrainingPeaks AutoSync path even safer strategically** — it's now a first-party Garmin channel, not a competitor's. Reinforces: ship TP AutoSync (wkov) now; the native Training API (yys2) is the eventual first-party path once the program reopens.
