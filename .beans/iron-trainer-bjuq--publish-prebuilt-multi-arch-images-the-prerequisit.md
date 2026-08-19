---
# iron-trainer-bjuq
title: Publish prebuilt multi-arch images (the prerequisite)
status: in-progress
type: epic
priority: high
created_at: 2026-08-18T16:09:34Z
updated_at: 2026-08-19T06:12:29Z
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
- [x] Decide native vs JVM — **JVM, decided 2026-08-19**. Native stays for Railway; the self-host image is JVM. Removes the cross-compile problem entirely (one buildx run covers amd64+arm64) and cuts CI from ~10 min/arch to ~1 min.
- [x] Added `backend-v2/Dockerfile.jvm` (separate file, not a build-arg branch — the
      two builds have genuinely different shapes). Reuses the stage-1 SPA build; image
      is 467 MB and the app uses 336 MB RSS at rest (db another 37 MB).
- [x] `.github/workflows/publish-image.yml` — builds + pushes on main, on v* tags, and
      on manual dispatch. Ends by pulling the pushed image back and booting it against a
      real Postgres, asserting both `/q/health` and that the SPA is actually baked in.
- [x] Multi-arch verified locally: `docker buildx build --platform linux/arm64,linux/amd64`
      succeeds (needs the docker-container driver; the default docker driver refuses
      multi-platform). Both build stages are pinned `--platform=$BUILDPLATFORM` so Maven
      and npm run natively ONCE and only the small JRE stage is emulated — confirmed in
      the build log, a single `RUN mvn -B package` for both targets.
- [x] Tags: `:latest` on main, semver on v* tags, `:sha-xxxxxxx` always so a self-hoster
      can pin and roll back.
- [ ] Make the package public so `docker pull` needs no auth
- [ ] Verify pull-and-run on a clean machine with no repo checkout at all

## Why
An athlete with Docker Desktop can run a container. They cannot be asked to install
a JDK, GraalVM and Maven, or to wait 10 minutes with a fan running.

## Progress 2026-08-19 — image + workflow landed, publish not yet proven

Decision recorded: **JVM**. Measured cost on a real build — 467 MB image, 336 MB RSS
for the app, 37 MB for Postgres. Acceptable on a laptop; the cross-compile saving is
the real win.

### Verified end to end locally
`docker compose up` against the locally built image gives a working app with **zero
configuration**: `/q/health` UP with the DB check passing, the SPA served from the
same origin (`assets/index-B_SUdrSP.js`, same hash as production), `/api/plan` 200,
and `/api/status` reporting `strava_configured:false, anthropic_configured:false,
auth_required:false, authenticated:true`. That last line is the milestone's core
promise demonstrated: no keys, no account, still usable.

(Note: an earlier probe of the same endpoint reported `strava_configured:true` — that
was my shell's exported env leaking into a bare `java -jar` run, not app behaviour.
The container result above is the honest one.)

### Landmine found on the way — worth its own bean

`backend-v2/mvnw` is **broken on any image without `unzip`**. The wrapper chooses its
download FORMAT from whether unzip exists (mvnw:178-182) and its download TOOL later
and independently (mvnw:194-199). With no unzip it fetches
`apache-maven-3.9.16-bin.tar.gz` while still validating against the `.zip` checksum in
`.mvn/wrapper/maven-wrapper.properties`, failing with:

```
Error: Failed to validate Maven distribution SHA-256, your Maven distribution
might be compromised.
```

which reads like a supply-chain compromise and is really a format mismatch. I
confirmed the pinned checksum is correct for the `.zip` by downloading it
independently.

The native Dockerfile escapes it only because its Mandrel/UBI9 base ships unzip.
**If Railway's builder base ever drops unzip, the production native build breaks the
same way.** `Dockerfile.jvm` installs unzip and keeps using `./mvnw`, so the
wrapper-pinned Maven 3.9.16 still applies. Filed as bean qpec.

(Correction: I first blamed the presence of `wget`, because `sh -x` showed wget being
selected right before the failure — adjacency, not causation. Disproved by experiment:
an image with wget AND unzip runs `./mvnw -version` fine. Caught in review on #121.)

### Still open
- [ ] Make the GHCR package public so `docker pull` needs no auth (must be done in
      the repo's package settings after the first publish — cannot be set from the
      workflow)
- [ ] Verify pull-and-run on a clean machine with no repo checkout. The workflow's
      smoke test covers pull-and-boot in CI, but not "a human with only Docker
      Desktop and a compose file"
- [ ] First real publish run — the workflow has never executed
