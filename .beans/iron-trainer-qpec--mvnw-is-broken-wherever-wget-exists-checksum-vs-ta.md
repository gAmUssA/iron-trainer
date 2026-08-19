---
# iron-trainer-qpec
title: mvnw is broken wherever wget exists (checksum vs .tar.gz mismatch)
status: todo
type: bug
priority: normal
created_at: 2026-08-19T06:12:50Z
updated_at: 2026-08-19T06:12:50Z
---

`backend-v2/mvnw` fails on any environment that has `wget` available, with an error
that looks like a security incident:

```
Error: Failed to validate Maven distribution SHA-256, your Maven distribution
might be compromised.
```

## Root cause (traced, not guessed)

`.mvn/wrapper/maven-wrapper.properties` pins:

```
distributionUrl=...apache-maven-3.9.16-bin.zip
distributionSha256Sum=5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce
```

`sh -x ./mvnw` shows the wrapper preferring wget and rewriting the URL to the
**.tar.gz**, then checking that file against the **.zip** checksum:

```
+ command -v wget
+ verbose Found wget ... using wget
+ wget --quiet .../apache-maven-3.9.16-bin.tar.gz -O /tmp/.../apache-maven-3.9.16-bin.tar.gz
+ echo 5af3b743...  /tmp/.../apache-maven-3.9.16-bin.tar.gz
+ sha256sum -c -
+ echo Error: Failed to validate Maven distribution SHA-256 ...
```

The pinned checksum is CORRECT for the .zip — downloaded it independently and it
matched byte for byte. The bug is that the wget branch fetches a different artifact
than the one the checksum describes.

## Why nothing has broken yet

The native `backend-v2/Dockerfile` runs `./mvnw` inside the Mandrel/UBI9 builder
image, which has curl and no wget. The curl branch fetches the `.zip`, the checksum
matches, Railway builds fine. Local dev works for the same reason (macOS has curl).

**This is one base-image change away from breaking the production native build**, and
the error message points at a supply-chain compromise rather than at the wrapper.

## Workaround already in place
`backend-v2/Dockerfile.jvm` avoids the wrapper entirely by using the `maven:*` image's
preinstalled `mvn`. That does not fix the wrapper.

## Todo
- [ ] Regenerate the wrapper (`mvn wrapper:wrapper`) or hand-fix the properties so the
      URL and checksum describe the same artifact
- [ ] Verify on an image with wget and no curl (e.g. `eclipse-temurin:21-jdk`) — that
      is the reproducer
- [ ] Consider whether the native Dockerfile should also stop depending on the
      wrapper, so the production build does not rest on "the base image happens to
      lack wget"
