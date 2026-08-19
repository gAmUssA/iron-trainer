---
# iron-trainer-thuc
title: One-command Docker Compose stack
status: todo
type: epic
priority: high
created_at: 2026-08-18T16:09:47Z
updated_at: 2026-08-18T16:16:46Z
parent: iron-trainer-sgfg
blocked_by:
    - iron-trainer-bjuq
---

The deliverable: an athlete downloads one file, runs one command, opens a browser.

## Target experience

```
1. Install Docker Desktop
2. curl -O https://raw.githubusercontent.com/gAmUssA/iron-trainer/main/docker-compose.yml
3. docker compose up
4. open http://localhost:8080
```

No repo clone, no .env editing required to *start* — the app must boot and be usable
with zero configuration, then ask for Strava credentials in-app when the user wants
to connect (see the local-mode epic).

## Todo
- [ ] `docker-compose.yml` at repo root: app + postgres:17 + a named volume
- [ ] Postgres healthcheck + `depends_on: condition: service_healthy` so the app
      doesn't race the DB on first boot
- [ ] Generate `SESSION_SECRET` automatically if unset rather than making the user
      invent one (currently defaults to empty)
- [ ] `.env.example` with ONLY the optional keys (Strava id/secret, Anthropic), each
      commented with what breaks if it is absent
- [ ] Fix `strava.redirect-uri` default: `localhost:8000` -> the actual serving port.
      Leftover from the decommissioned FastAPI service; wrong for exactly this case
- [x] Pinned to `ghcr.io/gamussa/iron-trainer:0.1` once v0.1.0 existed to pin to.
      `0.1` tracks 0.1.x patches only, so a pull brings fixes but never a breaking
      change (pre-1.0 the minor is where breaks live). Exact `:0.1.0` and
      `:sha-xxxxxxx` are published too, for harder freezes. Verified the pinned file
      boots: health 200, running image `ghcr.io/gamussa/iron-trainer:0.1`.
- [ ] Confirm the SPA is served from the same origin so no CORS setup is needed
- [ ] Test the full flow on a machine with no repo checkout

## Verified already
Booting the prod profile against an empty Postgres 17 works and migrates V1->V9
(see the milestone bean). The compose file does not need any Flyway overrides.
