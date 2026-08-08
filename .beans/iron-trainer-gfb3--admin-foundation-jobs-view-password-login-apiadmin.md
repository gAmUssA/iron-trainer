---
# iron-trainer-gfb3
title: Admin foundation + Jobs view (password login + /api/admin/jobs)
status: todo
type: feature
priority: high
created_at: 2026-08-08T10:29:35Z
updated_at: 2026-08-08T10:29:35Z
parent: iron-trainer-18n4
---

First slice + the primary debugging value. Password-protected admin (decoupled from users) + cross-athlete jobs inspection.

## Todo — auth foundation
- [ ] ADMIN_PASSWORD env (irontrainer.admin-password). Boot-safe: unset = admin disabled (login always 401), never crash.
- [ ] POST /api/admin/login {password} → constant-time compare vs ADMIN_PASSWORD; on match set signed admin_session cookie (reuse SessionCookie HMAC; httponly/secure/samesite=lax; e.g. 12h TTL). POST /api/admin/logout clears it.
- [ ] AdminGuard (@Provider filter or route) on /api/admin/* EXCEPT /login → 401 without a valid admin_session. Not tied to CurrentAthlete.
## Todo — jobs view
- [ ] GET /api/admin/jobs?kind=&status=&athlete_id=&limit=&offset= → cross-athlete, newest-first, paginated; each row: id, athlete_id, kind, status, created/started/finished, duration, error (truncated), has-result.
- [ ] GET /api/admin/jobs/{id} → full detail incl. result_json + full error.
- [ ] Frontend /admin: password login form → Jobs table (kind/status/athlete filters, status pills, click row → error/result inspector). Gated on a successful admin session.
## Security
- [ ] Guard MUST 401 non-admins; constant-time password compare; don't log the password; admin_session signed + httponly.
Follows worktree→build→review→merge→ADR.
