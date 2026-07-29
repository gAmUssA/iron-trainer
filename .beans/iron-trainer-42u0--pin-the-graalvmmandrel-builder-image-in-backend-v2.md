---
# iron-trainer-42u0
title: Pin the GraalVM/Mandrel builder image in backend-v2/Dockerfile
status: todo
type: task
priority: high
created_at: 2026-07-23T04:07:50Z
updated_at: 2026-07-23T04:07:50Z
---

backend-v2/Dockerfile uses the FLOATING tag quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-21. That tag rolled to a stricter GraalVM and silently broke the Railway native build (ClassNotFoundException on an nimbus→Tink optional class) even though CI (container-build with the Quarkus-pinned Mandrel) stayed green — a nasty CI-passes-but-deploy-fails gap. Fixed the immediate break by adding the Tink dep (PR #101), but the floating tag can roll again and break other things.

## Todo
- [ ] Pin the Dockerfile FROM to the exact Mandrel version Quarkus 3.37.3's container-build uses (so Railway == CI toolchain). Find it via the Quarkus 3.37.3 default quarkus.native.builder-image.
- [ ] Consider aligning the CI native job to the SAME pinned image so CI truly mirrors the Railway build (closes the gap that masked this).
- [ ] Bump the pin deliberately when upgrading Quarkus.

## Why
CI green must imply deploy-buildable. A floating builder tag breaks that invariant. [[backend-v2-railway-deploy]]
