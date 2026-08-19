---
# iron-trainer-t2a7
title: Add BootUI dev console to backend-v2 (dev mode only)
status: completed
type: task
priority: normal
created_at: 2026-08-18T16:16:40Z
updated_at: 2026-08-19T06:01:53Z
---

Add BootUI (https://www.julien-dubois.com/boot-ui/setup) as a development console
for backend-v2.

## Scope correction — read before starting

This was requested as "install Boot UI for local deployment so I can use their
integrations for better observability". Per BootUI's own setup docs, that is not
what it is:

- "Activates only in dev/local profiles or with DevTools"
- "**Disables itself in production profiles**"
- "Rejects non-loopback requests by default"
- Quarkus usage is `./mvnw quarkus:dev` — JVM dev mode

The self-hosted stack in milestone `sgfg` runs the **prod profile** in a container
(and, if we keep native, a GraalVM binary). BootUI would switch itself off there by
design, and a reflection-heavy Vue dev console is not something a native image will
serve anyway. So it cannot be the observability story for self-hosters — that is
epic `085u`, which uses the admin console we already have.

BootUI is still worth having, for **us**, in `quarkus:dev`. That is what this bean is.

## Compatibility
- Requires Java 17+ — we are on 21. Fine.
- Setup page documents Quarkus **3.33.3.1 LTS**; we are on **3.37.3**. The BootUI
  changelog references updating dependencies to Quarkus 3.37.2 and the shell
  displaying "Quarkus 3.37", so 3.37.x appears supported — but the setup page has
  not been updated, so **verify before assuming**.
- Artifact `com.julien-dubois.bootui:bootui-quarkus`, latest seen 1.14.0.

## Todo
- [ ] Confirm bootui-quarkus 1.14.0 actually resolves and runs against Quarkus 3.37.3
- [ ] Add the extension scoped so it can never reach a production build — a
      `<profile>` or `provided`/dev-only scope, not a plain compile dependency
- [ ] Verify `./mvnw quarkus:dev` serves it and that it is absent from the packaged
      jar and the native image (grep the built artifact, do not assume the profile
      guard worked)
- [ ] Check licensing before it becomes load-bearing — the setup page states no
      pricing or licence terms, which is worth resolving for a tool going into the
      build
- [ ] Document it in backend-v2/README as a dev-loop tool

## Why
Its panels (Hibernate, SQL tracing, config, GraalVM readiness) map well onto work we
actually do — the N+1 and native-image questions in this repo are recurring.

## Outcome: already done — no code change needed

Investigated 2026-08-18. **BootUI was already installed on `main`** at
`backend-v2/pom.xml:92` — `bootui-quarkus:1.12.0`, compile scope, with a comment
already noting it targets Quarkus 3.37.x. I should have grepped the pom before
researching compatibility; the answer was in the repo.

Verified rather than assumed:

- **Dev mode works.** `./mvnw quarkus:dev` logs
  `BootUI is available at http://localhost:8098/bootui`, `bootui` appears in
  Installed features, and the page returns HTTP 200.
- **Production is dark.** `/bootui`, `/bootui/` and `/bootui/api/info` on
  https://irontrainer.app all return **404**. The pom comment's claim holds.

## Rejected: hardening the scope to `provided`

I tried moving it to `<scope>provided</scope>` on the theory that "dev-only" should
be enforced by the build rather than trusted to the library. **It does not work** —
Quarkus still packaged all four jars (`bootui-quarkus`, `-core`, `-engine`, `-ui`)
into `target/quarkus-app/lib/main/`. Quarkus's app-model includes provided-scope
extension dependencies. Reverted.

If we ever do want it off the production classpath, the mechanism is an opt-in
Maven profile (`./mvnw quarkus:dev -Pbootui`), not a scope. Not worth doing today:
it costs every developer a flag to save image size on something already proven
dark at runtime.

## Remaining (low priority, not blocking)
- [ ] The four BootUI jars ride along in the packaged app — dead weight in the
      production image, no known exposure. Measure the size before deciding it matters
- [x] Licensing: **Apache License 2.0** (confirmed 2026-08-19 against the
      jdubois/boot-ui README — badge, explicit statement and LICENSE link; no
      dual-licensing and no commercial-use restriction). Permissive, fine to ship
      inside the production artifact.
- [ ] 1.12.0 -> 1.14.0 is available if we want the newer panels
