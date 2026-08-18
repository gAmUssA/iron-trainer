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
- [ ] Backup: a documented one-liner (`docker compose exec db pg_dump ...`) and
      ideally a button in the admin console that streams a dump to the browser
- [ ] Restore: the matching path, tested — a backup nobody has restored is not a backup
- [ ] **Upgrade test in CI**: boot the N-1 published image, seed data, pull N, assert
      Flyway migrates cleanly and the data survives. This is the single most valuable
      test in the milestone; every other bug costs an hour, a bad migration costs an
      athlete their training history
- [ ] Document where the data actually lives (named volume) and that
      `docker compose down -v` destroys it — the `-v` flag is a foot-gun
- [ ] Decide the retention/pruning story for a local install (the SaaS prunes old
      activities; a self-hoster probably wants to keep everything)
- [ ] Consider SQLite-or-Postgres. Postgres in compose is one more container but
      matches production exactly; a second DB dialect would fork every migration.
      Recommendation: stay on Postgres, revisit only if compose proves too heavy
