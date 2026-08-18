---
# iron-trainer-bjuq
title: Publish prebuilt multi-arch images (the prerequisite)
status: todo
type: epic
priority: high
created_at: 2026-08-18T16:09:34Z
updated_at: 2026-08-18T16:09:34Z
parent: iron-trainer-sgfg
---

Nothing else in this milestone is reachable until an athlete can `docker pull`
instead of `docker build`. Building the current Dockerfile means a ~10-minute
GraalVM native compile needing 5 GB of RAM.

## Decision to make first: native or JVM for the self-host image?

| | native (current prod image) | JVM |
|---|---|---|
| RAM at rest | ~60 MB | ~300 MB |
| Start | instant | ~2 s |
| CI build | ~10 min per arch | ~1 min |
| Cross-arch | **cannot cross-compile** — needs a runner per arch | trivial, one build runs anywhere |

Self-hosters are on arm64 Macs and amd64 Linux/Windows, so multi-arch is required
either way. Recommendation: **JVM image for self-hosting**, native stays for
Railway. A laptop has RAM to spare and 2 s of startup is invisible; halving the CI
matrix and removing the cross-compile problem is worth more. Revisit if anyone
tries to run this on a Raspberry Pi.

## Todo
- [ ] Decide native vs JVM (recommendation above — needs a call, it sets the CI shape)
- [ ] Add a JVM Dockerfile (or a build-arg branch in the existing one) that reuses the
      existing stage-1 SPA build so the web UI is still baked in
- [ ] GH workflow: build + push to ghcr.io/gamussa/iron-trainer on push to main and on tag
- [ ] Multi-arch: linux/amd64 + linux/arm64 (buildx; if native is chosen, a matrix over
      ubuntu-24.04 + ubuntu-24.04-arm instead, since native cannot cross-compile)
- [ ] Tag strategy: `:latest` for main, `:vX.Y.Z` for tags, `:sha-xxxxxxx` always
- [ ] Make the package public so `docker pull` needs no auth
- [ ] Verify pull-and-run on a clean machine with no repo checkout at all

## Why
An athlete with Docker Desktop can run a container. They cannot be asked to install
a JDK, GraalVM and Maven, or to wait 10 minutes with a fan running.
