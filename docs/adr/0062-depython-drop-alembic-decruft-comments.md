# 0062 — De-Python: drop alembic_version + de-parity comments (2026-08-09)

- **Status:** Accepted
- **Beans:** 73jd (drop alembic_version) + pek7 (comments) — epic y2yz

## Context

Continuing the de-Python cleanup (after ADR 0061). Two remaining safe items: a dead
Alembic table, and comments that claim byte-parity / a shared DB that no longer
exist and could mislead a maintainer into re-adding retired debt.

## Decision

- **Drop `alembic_version` (73jd).** `V8__drop_alembic_version.sql` —
  `DROP TABLE IF EXISTS "public"."alembic_version"`. Alembic managed the schema
  under the decommissioned FastAPI backend; Flyway owns migrations now (baselined
  at 2) and no code references the table.
- **De-parity the actively-misleading comments (pek7).** Reworded the handful of
  comments that assert "both backends", "shared-DB byte parity", or "byte-identical
  to FastAPI" (PyJson.loads, PlanResource.parseJson, FitnessTestsResource,
  Metrics, HrZones, FitnessTests) to state the real, current rationale
  (deterministic rounding / stable order / fail-loud parsing). Added a `ponytail:`
  ceiling note on `JobRunner.submitLock` (single-instance; DB advisory lock if it
  scales out).

## Deliberately NOT done

- **Class renames `PyJson`→`Json` / `Py`→`Round`** — skipped. Cosmetic, and the
  churn is real: `Py`'s banker's-rounding helpers back 150+ call sites and feed
  golden-value assertions, so a rename risks drift for zero functional gain (see
  research in bean y2yz). The classes are self-documenting post-ADR-0061.
- **Full sweep of the ~20 remaining "matches FastAPI …" provenance comments** —
  they're accurate historical context and harmless; reword opportunistically when
  a file is touched, not as a churn PR.
- **String-timestamp migration (x78x), Jackson-DTO conversion (gua2), cookie-scheme
  retirement (x8t8)** — still deferred (higher risk / live data).

## Consequences

- One fewer dead table; no comment now claims a byte-parity contract that the code
  doesn't honor. The de-Python epic's *safe* scope is complete; the remaining
  children are the deliberately-deferred high-risk ones.

## Verification

`V8` applies after `V7` in CI (Flyway migrate-at-start on Dev Services Postgres);
backend compiles; comment-only Java changes. No behavior change. Local review +
Copilot before merge.
