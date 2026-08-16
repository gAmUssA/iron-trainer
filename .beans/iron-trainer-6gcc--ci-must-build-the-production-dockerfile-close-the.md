---
# iron-trainer-6gcc
title: CI must build the production Dockerfile (close the CI-green-vs-deploy gap)
status: todo
type: task
priority: high
created_at: 2026-08-16T20:50:49Z
updated_at: 2026-08-16T20:50:49Z
---

Pinning the Mandrel digest (42u0 / ADR 0066) removed the builder-image DRIFT, but
"CI green ⇒ Railway-buildable" is still not true. Copilot's review of PR #116
identified the remaining gap correctly:

- Railway runs `./mvnw package -Dnative` INSIDE the pinned jdk-25 builder image,
  so Maven resolution + Quarkus augmentation run on JDK 25.
- CI runs those on the runner's Temurin 21 (`setup-java`, backend-v2.yml) and
  containerizes only the `native-image` compiler step.
- CI never builds `backend-v2/Dockerfile` at all: the node:22-alpine SPA stage,
  the injection of `frontend/dist` into `META-INF/resources`, `dependency:go-offline`,
  and the ubi9-minimal runtime stage are entirely unexercised.

So a JDK-25-only augmentation failure, or any Docker-stage failure, still ships
green CI and a broken deploy — exactly the failure mode [[backend-v2-railway-deploy]]
keeps hitting.

## Todo
- [ ] Add a CI job that runs `docker build -f backend-v2/Dockerfile .` from the repo root
- [ ] Smoke-run the resulting image against the Postgres service (reuse the existing
      `timeout 15 ... test "$code" -eq 124` pattern from the native job)
- [ ] Decide whether it replaces the current `native` job or runs alongside it — building
      the Dockerfile does a superset of that job's work, so keeping both likely wastes
      ~10 min per run
- [ ] If kept separate, gate it (paths filter / merge-queue only) — a full native Docker
      build is slow and should not run on every push
- [ ] Once green, restore the "CI green ⇒ Railway-buildable" claim in ADR 0066 and the
      backend-v2.yml comment, both of which were deliberately narrowed in PR #116

## Why
The whole point of 42u0 was the invariant, not the pin. The pin was the cheap half.
