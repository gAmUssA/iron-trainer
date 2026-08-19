---
# iron-trainer-qpec
title: mvnw is broken wherever unzip is missing (checksum vs .tar.gz mismatch)
status: todo
type: bug
priority: normal
created_at: 2026-08-19T06:12:50Z
updated_at: 2026-08-19T06:12:50Z
---

`backend-v2/mvnw` fails on any environment WITHOUT `unzip`, with an error that looks
like a security incident:

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

The wrapper picks its download FORMAT from whether `unzip` exists (mvnw:178-182),
and only later picks its download TOOL (mvnw:194-199). Those are independent:

```sh
# select .zip or .tar.gz
if ! command -v unzip >/dev/null; then
  distributionUrl="${distributionUrl%.zip}.tar.gz"
```

With no unzip it fetches the **.tar.gz** and validates it against the **.zip**
checksum. `sh -x ./mvnw` on an image with neither unzip nor curl:

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
image, which HAS unzip, so the URL is never rewritten and the checksum matches. Local
dev works for the same reason (macOS has unzip).

**This is one base-image change away from breaking the production native build** — if
Railway's builder base ever drops unzip — and the error points at a supply-chain
compromise rather than at the wrapper.

## Correction

An earlier version of this ticket blamed the presence of `wget`. That was wrong:
`sh -x` showed wget being selected immediately before the failure and I read
causation into adjacency. Verified by experiment — an image with **wget AND unzip**
runs `./mvnw -version` successfully (prints 3.9.16). The download tool is irrelevant;
`curl` without `unzip` fails identically. Caught in review on PR #121.

## Workaround already in place
`backend-v2/Dockerfile.jvm` installs `unzip` in its build stage and keeps using
`./mvnw`, so the wrapper's pinned Maven version still applies. That does not fix the
wrapper.

## Todo
- [ ] Regenerate the wrapper (`mvn wrapper:wrapper`) or hand-fix the properties so the
      URL and checksum describe the same artifact
- [ ] Reproducer: any image without `unzip` (e.g. bare `eclipse-temurin:21-jdk`).
      Adding unzip fixes it; swapping wget for curl does NOT
- [ ] Consider whether the native Dockerfile should stop resting on "the base image
      happens to ship unzip"
