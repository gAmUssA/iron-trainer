# Backend migration research: Python/FastAPI → Quarkus + GraalVM (July 2026)

Bean: iron-trainer-xh5f. Grounded in the actual backend (~7k LOC, 11 routers,
11 Alembic revisions, 194 tests) + verified July-2026 versions.

## Verdict

**Not before Sep 26 (race).** Zero burning-platform problems; this is want,
not need. After the race: a strangler migration is genuinely viable — the
historical blockers are gone. Next Quarkus LTS ships ~Sep 2026; start
Phase 0/1 in October, Python decommission by year-end. Effort: ~21-29
person-days over 2-3 months part-time.

**Weekend spike first (risk retirement):** one Quarkus 3.33+ app proving
(1) Hibernate/Panache + quarkus-jdbc-sqlite(+sqlite4j) with community
SQLiteDialect, (2) Flyway V1 baseline on SQLite + Dev-Services Postgres,
(3) anthropic-java 2.48.0 forced-tool-schema call into records,
(4) com.garmin:fit encode→decode round-trip, (5) native build in GH Actions.
All five pass → the rest is typing, not research.

## Key version facts (verified)

- Quarkus 3.33 = current LTS (Mar 2026→Mar 2027); 3.36.3 latest stream.
- Official Anthropic Java SDK com.anthropic:anthropic-java 2.48.0 — mature,
  tool use + structured outputs (outputFormat(Class<T>) derives schema from a
  record — better ergonomics than our hand-written STEP_SCHEMA).
  Alt: quarkus-langchain4j-anthropic 1.9.2 (handles native config).
- Garmin FIT SDK is OFFICIAL Java on Maven Central: com.garmin:fit 21.205.0 —
  an upgrade over our community Python fit-tool.
- SQLite: quarkus-jdbc-sqlite (xerial, native-mode ok) or sqlite4j (pure-Java
  WASM build). Hibernate dialect is community-tier; Dev Services Postgres is
  the escape hatch (test suite already has a --pg path).
- Native image: Hibernate/Flyway/Agroal/PG driver officially supported, no
  manual reflection JSON. Build native in GitHub Actions (~5-8 GB build RAM),
  Railway deploys the prebuilt ubi-minimal image (~50-80 MB).

## Component mapping (abridged)

routers → JAX-RS resources · SQLModel → Hibernate/Panache · Alembic → Flyway
(SQUASH to V1 baseline + baseline-on-migrate; history frozen, not ported) ·
ContextVar tenancy → CDI @RequestScoped (strictly nicer) · bearer auth →
custom HttpAuthenticationMechanism (SHA-256 lookup ports directly; session
cookie = itsdangerous-compatible HMAC verify, ~1 day, do LAST) · thread jobs →
virtual threads, same job-row-truth design · Strava → REST Client interface ·
frontend → Quinoa · health → smallrye-health.

## Strategy: strangler (Option B) + contract tests as Phase 0

Quarkus owns the domain, reverse-proxies unmigrated paths to Python over
Railway private network; both share one Postgres (safe: DB-row job truth,
per-request tenancy). Order: contract-test extraction (2-3pd; pytest suite is
in-process/monkeypatched — CANNOT point at a URL as-is) → skeleton+proxy →
domain math → export/FIT → Strava+jobs → LLM → auth last. Freeze schema churn
during the window.

## Gained / lost

Gained: 20-60ms starts + ~30-70MB RSS (vs 150-300MB), typed domain math,
official FIT SDK, Viktor's home-turf ecosystem (+ DevRel content value).
Lost/risked: rewrite risk vs working product, Python LLM/numpy conveniences
(keep scripts/ scratch area), double maintenance during strangle, 194 tests
re-earned in JUnit.

## Decision note (Viktor, 2026-07-15)

**LangChain4j is the mandated LLM integration layer** for the migration —
use quarkus-langchain4j-anthropic (1.9.2+: AI Service interfaces, POJO
structured outputs, build-time native-image wiring), NOT the raw Anthropic
Java SDK. The official com.anthropic:anthropic-java SDK remains the sanctioned
FALLBACK for the one plan-generation call if LangChain4j's structured output
turns out to be prompt-coaxed rather than schema-enforced — that check is
spike item (2): assert the wire request uses Anthropic's native strict-schema
path before trusting the abstraction.
