# Architecture Decision Records

This directory records the **why** behind significant architecture and design
decisions in Iron Trainer — not just what the code does, but the context, the
options weighed, and the trade-offs accepted.

## Convention

- Every new feature is built in its own **git worktree** (branch isolated from
  `main`); when it's implemented, an ADR is written and committed on that branch
  so it lands in the PR.
- Files are named `NNNN-short-kebab-title.md`, numbered sequentially.
- Format (lightweight [MADR](https://adr.github.io/madr/)): **Status · Context ·
  Decision · Alternatives considered · Consequences · Implementation notes ·
  Verification**.
- ADRs are immutable once **Accepted**. To change a decision, write a new ADR that
  **supersedes** the old one (note it in both).

ADRs 0001–0004 were written **retroactively** to capture decisions made before this
convention existed (the greenfield build + the two shipped feature PRs).

## Index

| # | Title | Status |
|---|-------|--------|
| [0001](0001-database-abstraction-sqlmodel-alembic.md) | Database abstraction: SQLModel + Alembic, SQLite local / Postgres prod | Accepted |
| [0002](0002-multi-user-login-with-strava.md) | Multi-user: Login with Strava + per-athlete data isolation | Accepted |
| [0003](0003-apple-workouts-export-workoutkit.md) | Apple Workouts export via `.itw` + WorkoutKit iOS helper app | Accepted |
| [0004](0004-device-pairing-live-sync.md) | In-app login + live plan sync via device-pairing bearer tokens | Accepted |
| [0005](0005-fitness-tests-library.md) | Fitness Tests library (measure thresholds + schedulable test workouts) | Accepted |
| [0006](0006-display-units-preference.md) | Display units preference (miles/km + human-readable durations) | Accepted |
| [0007](0007-strava-compliance.md) | Strava OAuth UX hardening + brand/policy compliance | Accepted |
| [0008](0008-strava-bulk-import.md) | Strava bulk-export (GDPR archive) importer | Accepted |
| [0009](0009-nutrition-and-fueling.md) | Nutrition & Fueling (per-workout, daily & race-day fueling, LLM-enhanced) | Accepted |
| [0010](0010-security-hardening.md) | Security & robustness hardening from the full-stack review | Accepted |
| [0011](0011-trends-insights.md) | Trends page: insights (trendlines, verdicts, intensity mix, CTL trajectory, PRs) | Accepted |
| [0012](0012-ios-today-view-widgets-liquid-glass.md) | iOS Today view, WidgetKit widgets & Liquid Glass pass | Accepted |
| [0013](0013-hr-zones.md) | HR zones: calculator, zone-based prescriptions, AI planner integration | Accepted |
| [0014](0014-weekly-checkin.md) | Weekly Check-in: one-tap adaptive loop (sync → reconcile → replan, narrated) | Accepted |
| [0015](0015-async-jobs.md) | Async background jobs for Strava/Claude operations (DB-tracked, no broker) | Accepted |
| [0016](0016-daily-readiness-call.md) | Daily readiness call: ACWR-based go hard / go easy / rest | Accepted |
| [0017](0017-chart-time-windows.md) | Backend-driven time windows for charts (PMC, trends) | Accepted |
| [0018](0018-checkin-feel-and-notifications.md) | Feel-vs-data check-in + local notifications (reminder & morning brief) | Accepted |
| [0019](0019-health-auto-export-ingestion.md) | Recovery-data ingestion via Health Auto Export (sleep/HRV/RHR → readiness) | Accepted |
| [0020](0020-backend-v2-quarkus-strangler.md) | Backend v2: Quarkus strangler migration decisions | Accepted |
| [0021](0021-web-ui-information-architecture.md) | Web UI information architecture: tabs map to questions (Settings/Fitness moves) | Accepted |
| [0022](0022-backend-v2-session-cookie-verification.md) | Backend v2: session-cookie verification (Phase 7 keystone, 2026-07-17) | Accepted |
| [0023](0023-strangler-write-forwarding.md) | Strangler write forwarding (POST/cookies) with asymmetric fallback | Accepted |
| [0024](0024-backend-v2-races-vertical.md) | Backend v2: races vertical | Accepted |
| [0025](0025-backend-v2-analytics-reads.md) | Backend v2: analytics reads (weekly volume + activities feed) | Accepted |
| [0026](0026-backend-v2-trends-vertical.md) | Backend v2: trends vertical (insights engine) | Accepted |
| [0027](0027-backend-v2-race-readiness.md) | Backend v2: race-readiness projection | Accepted |
| [0028](0028-backend-v2-nutrition-llm.md) | Backend v2: nutrition race-day LLM regenerate | Accepted |
| [0029](0029-backend-v2-async-job-envelope.md) | Backend v2: async job envelope for write endpoints | Accepted |
| [0030](0030-backend-v2-idempotency-keys.md) | Backend v2: idempotency keys for proxied writes | Accepted |
| [0031](0031-backend-v2-plan-read.md) | Backend v2: plan read (GET /api/plan) | Accepted |
| [0032](0032-backend-v2-plan-compliance.md) | Backend v2: plan compliance (GET /api/plan/compliance) | Accepted |
| [0033](0033-backend-v2-plan-generate.md) | Backend v2: plan generation (POST /api/plan/generate) | Accepted |
| [0034](0034-backend-v2-plan-replan-week.md) | Backend v2: replan one week (POST /api/plan/replan-week) | Accepted |
| [0035](0035-backend-v2-plan-reconcile.md) | Backend v2: reconcile (POST /api/plan/reconcile) | Accepted |
| [0036](0036-backend-v2-plan-checkin.md) | Backend v2: weekly check-in (POST /api/plan/checkin) | Accepted |
| [0037](0037-backend-v2-strava-oauth-connect.md) | Backend v2: Strava OAuth part 1 — session minting + connect | Accepted |
| [0038](0038-backend-v2-strava-oauth-callback-disconnect.md) | Backend v2: Strava OAuth part 2 — callback (login) + disconnect | Accepted |
| [0039](0039-backend-v2-strava-gdpr-archive-import.md) | Backend v2: Strava GDPR archive import | Accepted |
| [0040](0040-backend-v2-app-tail-status-health-profile.md) | Backend v2: app tail — health, status, me, logout, profile read | Accepted |
| [0041](0041-backend-v2-health-recovery-vertical.md) | Backend v2: health vertical — recovery read + Health-Auto-Export ingest | Accepted |
| [0042](0042-backend-v2-profile-update.md) | Backend v2: PUT /api/athlete/profile (edit thresholds) | Accepted |
| [0043](0043-backend-v2-export-zip-bundles.md) | Backend v2: export ZIP bundles (plan.zip + week.zip) | Accepted |
| [0044](0044-backend-v2-device-pairing.md) | Backend v2: device pairing (native-app token minting) | Accepted |
| [0045](0045-health-auto-export-server-adoption.md) | Adopt Health Auto Export payload breadth + Grafana (investigation) | Accepted |
| [0046](0046-hae-expanded-metric-mapping.md) | Expanded Health Auto Export metric mapping (FTP, HR recovery, load) | Accepted |
| [0047](0047-recovery-trends-and-ios-glance-widget.md) | Recovery trends (web) + readiness glance widget (iOS) | Accepted |
| [0048](0048-native-healthkit-ingestion-auth-foundation.md) | Native HealthKit ingestion: authorization & entitlements foundation | Accepted |
| [0049](0049-healthkit-reader-layer-delta-sync.md) | HealthKit reader layer: anchored delta-sync contract | Accepted |
| [0050](0050-healthkit-night-assembler.md) | HealthKit night assembler + first unit-test target | Accepted |
| [0051](0051-native-healthkit-ingest-and-delivery.md) | Native HealthKit ingest client + delivery | Accepted |
