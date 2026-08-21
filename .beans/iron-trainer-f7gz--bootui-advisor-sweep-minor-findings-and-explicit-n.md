---
# iron-trainer-f7gz
title: 'BootUI advisor sweep: minor findings and explicit non-issues'
status: todo
type: task
priority: low
created_at: 2026-08-21T14:23:11Z
updated_at: 2026-08-21T14:23:11Z
---

Low-value findings from the BootUI advisor sweep (2026-08-21), kept together so they
are recorded without cluttering the backlog. None is urgent; several are arguably
"won't do".

## Worth doing eventually
- **`AppleAuth.processor` is shared mutable state on an `@ApplicationScoped` bean**
  (QA-CDI-001). Singleton + non-final field. Check whether the processor is
  thread-safe; make it `private final` if so.
- **Package cycles between slices** (ARCH-PKG-001, 51 cycles). Real: `activity ->
  auth -> athlete -> …` closes loops through shared entities. The vertical-slice
  layout is deliberate and the cycles come from entities being shared across slices,
  so this is a genuine-but-large refactor. Record, do not chase.
- **Graceful shutdown never configured** (QA-WEB-004). `quarkus.shutdown.timeout`
  unset means SIGTERM exits immediately without draining in-flight requests. On
  Railway that can cut a sync or an import mid-write. One line; slightly real.
- **HTTP response compression off** (QA-WEB-001). The app serves a JS bundle and
  JSON; `quarkus.http.enable-compression=true` is free bandwidth.

## Deliberately not doing
- **"Possible secret in configuration" (CRITICAL, 21)** — FALSE POSITIVE. It matches
  on key NAMES, flagging `max-tokens`, `password-parameter` and `target-level`
  alongside real ones. Every production value is a `${ENV_VAR:}` reference. The only
  literals are `%test` dummies (`test-admin-pw`, `test-secret-key`), scoped to the
  test profile.
- **"Duplicate route mappings" (HIGH)** — FALSE POSITIVE. It flags
  `StravaApi#token`/`#exchangeCode` and `WhoopApi#exchangeCode`/`#refresh`, which are
  `@RegisterRestClient` CLIENT interfaces, not server endpoints. Two client methods
  may absolutely share a URL.
- **Untyped `Map` response bodies (67)** — deliberate. The Map-based responses are
  the FastAPI-parity design; typing them all is a large churn with no user-visible
  gain.
- **Field injection (73)** — consistent house style across the whole codebase.
- **`GenerationType.IDENTITY` blocks JDBC batching (HIGH)** — already known and
  already mitigated where it mattered: `MetricDaily` was deliberately given a
  composite natural key so its bulk inserts batch (see the comment in
  application.properties about the 28s -> 499ms fix). Remaining IDENTITY entities
  insert in small numbers.
- **Swap pressure (MEDIUM, memory advisor)** — that is the dev laptop, not the app.
- **No virtual threads (83 endpoints)** — a real option, but a performance change to
  make with a measurement, not because a linter counted annotations.
