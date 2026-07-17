---
# iron-trainer-s6v3
title: 'backend-v2: async job envelope for write endpoints (?async=1 → {job})'
status: todo
type: task
priority: low
created_at: 2026-07-17T00:17:36Z
updated_at: 2026-07-17T00:17:36Z
---

FastAPI write endpoints accept ?async=1 (alias run_async) and return {"job": id}; the client polls GET /api/jobs/{id}. backend-v2 ports (dedup, and earlier record/regen paths) run synchronously and ignore the async param — a divergent contract for async callers. v2 has a JobRunner; wire the async envelope for these write endpoints once they take traffic (Phase 7 / write flip [[iron-trainer-93o6]]). Dormant until then (POST endpoints aren't proxied). Source: code-review of feature/strava-dedup (StravaResource async path).
