---
# iron-trainer-18n4
title: Admin console — users, jobs & sync debugging
status: completed
type: epic
priority: high
created_at: 2026-08-08T10:23:11Z
updated_at: 2026-08-11T12:52:47Z
---

An internal admin/ops UI so Viktor can see users, inspect background jobs (running + ran), and investigate sync failures (Strava, Apple Health, dedup, check-in, import). Debugging tool first, pretty second.

## What exists (2026-08-08)
- Job entity (backend-v2 jobs/Job.java): id, athlete_id, kind, status (queued/running/succeeded/failed), created_at, started_at, finished_at, result_json, error. Kinds seen: strava_sync, dedup, checkin, import (+ plan_*). Perfect for the admin — but JobResource only exposes GET /api/jobs/{id} (athlete-scoped) + /summary. NO cross-athlete listing.
- Athlete entity: id, name, strava_athlete_id, apple_user_id, ftp/thresholds/etc. (no created_at column).
- NO admin concept exists (no is_admin/role). Auth = session cookie / bearer via CurrentAthlete + BearerAuthFilter; Strava login gated by irontrainer.allowed-strava-ids.

## Design
**Auth/gating — PASSWORD-PROTECTED, DECOUPLED from user accounts (per Viktor 2026-08-08):** admin access is a SHARED PASSWORD, not tied to any athlete. Backend: ADMIN_PASSWORD env; POST /api/admin/login {password} → on match, set a signed admin_session cookie (reuse SessionCookie HMAC; httponly, secure, samesite=lax, short TTL). An AdminGuard filter protects /api/admin/* (except /login) → 401 without a valid admin_session. Admin endpoints do NOT use CurrentAthlete — they query across ALL athletes/jobs. Frontend: an /admin area with its own password login form → admin console. Alternative considered: HTTP Basic Auth (ADMIN_USER/ADMIN_PASSWORD) — simpler but clunkier in the SPA + no clean logout; the signed-cookie login is preferred. No is_admin on /api/me (admin isn't a user property). Never rely on the UI hiding the nav — the guard enforces.
**Backend:** new AdminResource @Path('/api/admin'): GET /users, GET /jobs (cross-athlete; filter kind/status/athlete_id; newest-first; paginated; error + durations), GET /jobs/{id} (detail incl. result_json), GET /users/{id} (connected accounts, last-sync-per-kind, recent jobs, counts).
**Frontend:** an Admin section in the React SPA gated on me.is_admin — Jobs table (kind/status filters, status pills, error/result inspection) + Users table/detail. Served by backend-v2 like the rest of the SPA.

## Slices (each its own PR, worktree→build→review→merge→ADR)
1. Admin foundation + Jobs view (auth gate + is_admin + /api/admin/jobs list/detail + jobs UI) — the primary debugging value.
2. Admin Users view (/api/admin/users + detail + UI).
3. Sync-health telemetry (failure rates per kind, recent failures across users — 'which backend is failing').

## Security
Admin endpoints MUST 403 for non-admins (never rely on the UI hiding the nav). Don't leak other users' PII beyond what ops needs. Reuse the existing session/bearer auth; admin is an allowlist on top.

## Summary
All children shipped + live: foundation+Jobs (gfb3/#105), Users (y8b2/#106), sync-health (j41l/#107), p50/p95 durations (og06/#114), daily failure trend (8vdj/#115). Password-gated admin console at /admin with Health/Ingests/Users/Jobs tabs. ADRs 0056-0058, 0064-0065.
