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
