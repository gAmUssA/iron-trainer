---
# iron-trainer-yijb
title: 'Data lifecycle: backup, restore, safe upgrades'
status: todo
type: epic
priority: high
created_at: 2026-08-18T16:16:12Z
updated_at: 2026-08-18T16:16:46Z
parent: iron-trainer-sgfg
blocked_by:
    - iron-trainer-bjuq
---

On SaaS, upgrades and backups are our problem. On a laptop they become the athlete's
problem, and they will not think about either until something is already lost.

## Todo
- [x] Backup + restore documented in docs/self-host.md and TESTED end to end (the
      commands as written): `pg_dump --clean --if-exists` produces 16 DROP guards,
      and restoring into an ALREADY-INITIALISED database with `ON_ERROR_STOP=1`
      exits 0 with zero errors and a healthy app afterwards. The first draft was
      broken exactly as a reviewer predicted (`relation already exists`, partial
      restore); a backup nobody has restored is not a backup.
- [ ] Admin-console button that streams a dump to the browser (the CLI path works;
      this is the non-technical-user version)
- [x] **Upgrade test in CI** — `backend-v2/scripts/upgrade-test.sh`, wired into
      publish-image.yml after the push. Boots the previous release against a real
      Postgres, seeds a fitness-test result through the real API, then boots the
      newly pushed image against that same database and asserts the rows survived
      AND are still readable through the API (a migration can leave a table intact
      and still break the mapping).

      **Verified it actually gates**, with a throwaway `DROP TABLE` V10: the
      migration SUCCEEDED, the schema advanced 9 -> 10, and the app came up
      HEALTHY — a health check alone would have passed it. Only the row-count
      assertion caught it: `row count changed across the upgrade: 1 -> 0`, exit 1.
      That is the whole argument for asserting on data rather than on liveness.
- [ ] Document where the data actually lives (named volume) and that
      `docker compose down -v` destroys it — the `-v` flag is a foot-gun
- [ ] Decide the retention/pruning story for a local install (the SaaS prunes old
      activities; a self-hoster probably wants to keep everything)
- [ ] Consider SQLite-or-Postgres. Postgres in compose is one more container but
      matches production exactly; a second DB dialect would fork every migration.
      Recommendation: stay on Postgres, revisit only if compose proves too heavy
