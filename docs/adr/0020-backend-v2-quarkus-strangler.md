# 0020 — Backend v2: Quarkus strangler migration (started 2026-07-15)

Date: 2026-07-15
Status: Accepted
Milestone: iron-trainer-37md · Research: docs/research/quarkus-graalvm-migration.md

## Context

Research (bean xh5f) recommended a post-race strangler migration to Quarkus +
GraalVM. Viktor overrode the timing: start now, strangler discipline intact —
the working FastAPI app stays authoritative while verticals migrate one at a
time behind a shared Postgres.

## Decisions

1. **Monorepo `backend-v2/`**, Quarkus 3.37 / Java 21; REST-Jackson,
   Hibernate Panache, Flyway, SmallRye Health; Dev Services Postgres for
   dev/test (SQLite has no v2 story — by design).
2. **Schema authority**: `V1__baseline.sql` = `supabase db dump
   --schema-only` of the LIVE prod DB (strict — no IF NOT EXISTS; empty-DB
   only). Prod runs `baseline-on-migrate`; Alembic keeps owning migrations
   until the Flyway handover (Phase 7).
3. **Auth seam**: bearer device tokens (SHA-256 → shared device_token table)
   port first via `BearerAuthFilter` + `CurrentAthlete` @RequestScoped
   (ContextVar → CDI). Session cookies stay FastAPI-side until Phase 7 —
   this defines which routes can strangle early (bearer-only: exports).
4. **LLM**: LangChain4j mandated (Viktor); wire-proven that AI Services with
   `RESPONSE_FORMAT_JSON_SCHEMA` capability emit Anthropic's NATIVE
   output_config json_schema (spikes/lc4j-structured-output). The capability
   flag is mandatory — silent prompt fallback without it.
5. **FIT**: official com.garmin:fit SDK; conventions preserved (power +1000,
   HR +100) but durations FIT-SPEC-CORRECT (ms scale) — a deliberate
   divergence from the Python exporter's unscaled-duration bug
   (iron-trainer-sqib). v2 does not reproduce defects.
6. **Verification**: 11-test black-box contract suite
   (backend/contract_tests/, CONTRACT_BASE_URL) is the cross-implementation
   gate; per-vertical CI (backend-v2.yml: JVM tests + native image build +
   binary smoke-run against Postgres).
7. **Dark deploy**: second Railway service `backend-v2` (JVM-mode Dockerfile
   first; native via GHCR later), DATABASE_URL shared by reference variable
   and split to JDBC parts in start.sh, IRONTRAINER_AUTH_REQUIRED=true (no
   default-athlete fallback in prod). No traffic until export routing flips.

## Status at time of writing

Phase 0-1 complete (PRs #30-#33); exports vertical endpoints complete
(.itw/.zwo/.fit/plan.itw — PRs #34-#36); dark deploy in progress.
