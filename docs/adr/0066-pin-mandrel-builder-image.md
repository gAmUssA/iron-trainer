# 0066 — Pin the Mandrel native-build image by digest (2026-08-11)

- **Status:** Accepted
- **Bean:** 42u0 (high) · relates to [[backend-v2-railway-deploy]]

## Context

The Railway `backend-v2/Dockerfile` built the GraalVM native image `FROM
quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:**jdk-21**` — a **floating** tag.
CI's `native` job builds with `-Dquarkus.native.container-build=true`, which pulls
**Quarkus 3.37.3's default** builder image — which is actually **`:jdk-25`**. So two
problems compounded:

1. **Drift:** Railway built on a *different* JDK/Mandrel (`jdk-21`) than CI validated
   (`jdk-25`) — "CI green" did not imply "Railway-buildable".
2. **Float:** both tags roll. A `:jdk-21` roll to a stricter GraalVM once silently
   broke the Railway build (nimbus→Tink `ClassNotFoundException`) while CI stayed
   green — the exact CI-passes-but-deploy-fails trap.

## Decision

Pin **both** build paths to the **same immutable digest** — the image `:jdk-25`
currently resolves to (`sha256:93bcce120e…`, tag `jdk-25.0.4`, Quarkus 3.37.3's
default):

- **Dockerfile** `FROM …@sha256:93bcce120e…` (Railway).
- **CI** native step adds
  `-Dquarkus.native.builder-image=…@sha256:93bcce120e…` (so container-build uses the
  identical image, not the floating `:jdk-25`).

Both paths now compile the native image with the byte-identical toolchain, and
neither floats.

**Scope — what this does NOT establish.** Only the containerized `native-image`
step is identical. Railway runs `./mvnw package -Dnative` *inside* the pinned
jdk-25 image, so Maven resolution and Quarkus augmentation run on JDK 25 there;
CI runs those on the runner's Temurin 21 and containerizes only the compiler.
CI also never builds `backend-v2/Dockerfile` — the SPA stage, the injection of
`frontend/dist` into `META-INF/resources`, `dependency:go-offline` and the
ubi9-minimal runtime stage are all unexercised. A JDK-25-only augmentation
failure, or any Docker-stage failure, therefore still passes CI. This ADR closes
the *builder-image drift* hole, not the general "CI green ⇒ deploy-buildable"
gap; see Consequences.

## Alternatives considered

- **Pin to a version tag (`:jdk-25.0.4`)** — readable, but version tags can in
  principle be re-pushed; a digest is truly immutable. Comment records the version.
- **Keep Railway on `jdk-21`, pin CI to `jdk-21`** — rejected: aligns to a non-default
  toolchain; better to match Quarkus 3.37.3's actual default (jdk-25), which CI's
  native job already builds green.

## Consequences

- Moving Railway `jdk-21 → jdk-25` is a real toolchain bump. CI's native job builds
  green on this exact image, so the *compiler* is validated — but CI augments on
  Temurin 21, so JDK-25 augmentation on Railway is still first exercised at deploy
  time. Post-merge deploy verification is not optional here.
- **The invariant the bean set out to establish is still open.** Making CI actually
  build and smoke-test `backend-v2/Dockerfile` is the only thing that would deliver
  "CI green ⇒ Railway-buildable"; tracked as bean 6gcc rather than bundled here,
  since it is a new (slow) CI job rather than a one-line pin.
- **Upgrade discipline:** on a Quarkus bump, re-resolve `:jdk-NN` to its new digest
  and update **both** the Dockerfile and the CI arg together (comments say so).

## Verification

CI `native` builds green with the pinned digest. **Railway deploy must be verified
after merge** (this bean exists precisely because CI green ≠ deploy-healthy) — confirm
the deploy builds and serves, per [[backend-v2-railway-deploy]].
