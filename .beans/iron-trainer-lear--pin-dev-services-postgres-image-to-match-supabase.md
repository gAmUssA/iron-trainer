---
# iron-trainer-lear
title: Pin Dev Services Postgres image to match Supabase major version
status: todo
type: task
priority: low
created_at: 2026-07-23T03:14:58Z
updated_at: 2026-07-23T03:14:58Z
---

backend-v2 dev/test already run on Quarkus Dev Services Postgres (Testcontainers) — no SQLite/H2 anywhere (that was the decommissioned FastAPI stack). The only refinement for local/test↔prod parity: Dev Services currently uses the default Postgres image, which may differ from Supabase's Postgres major version.

## Todo
- [ ] Check Supabase's Postgres major version (dashboard → infra).
- [ ] Pin it: quarkus.datasource.devservices.image-name=postgres:<N> (or pgvector/etc. if Supabase uses an extension image) in application.properties.
- [ ] Optionally pin quarkus.datasource.devservices.reuse=true for faster local iteration.
- [ ] Verify test suite + quarkus:dev still boot against the pinned image.

## Why
Keeps local/test on the exact Postgres engine version as prod so version-specific SQL/behavior can't diverge silently. Requires Docker/Podman locally (already the case for Dev Services).
