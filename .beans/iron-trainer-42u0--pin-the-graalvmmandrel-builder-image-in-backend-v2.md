---
# iron-trainer-42u0
title: Pin the GraalVM/Mandrel builder image in backend-v2/Dockerfile
status: completed
type: task
priority: high
created_at: 2026-07-23T04:07:50Z
updated_at: 2026-08-12T01:34:29Z
---

backend-v2/Dockerfile uses the FLOATING tag quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-21. That tag rolled to a stricter GraalVM and silently broke the Railway native build (ClassNotFoundException on an nimbus→Tink optional class) even though CI (container-build with the Quarkus-pinned Mandrel) stayed green — a nasty CI-passes-but-deploy-fails gap. Fixed the immediate break by adding the Tink dep (PR #101), but the floating tag can roll again and break other things.

## Todo
- [x] Pin the Dockerfile FROM to the exact Mandrel version Quarkus 3.37.3's container-build uses (so Railway == CI toolchain). Find it via the Quarkus 3.37.3 default quarkus.native.builder-image.
- [x] Aligned the CI native job to the SAME pinned image so CI truly mirrors the Railway build (closes the gap that masked this).
- [x] Documented: bump the pin deliberately when upgrading Quarkus (comments in both Dockerfile + workflow).

## Why
CI green must imply deploy-buildable. A floating builder tag breaks that invariant. [[backend-v2-railway-deploy]]

## Summary of Changes
Pinned the Mandrel builder image by DIGEST (sha256:93bcce…, = jdk-25.0.4, Quarkus 3.37.3 default) in BOTH backend-v2/Dockerfile FROM and the CI native step (-Dquarkus.native.builder-image). Fixes the jdk-21(Railway) vs jdk-25(CI) drift + the floating-tag risk. ADR 0066. Deploy verification required post-merge.
