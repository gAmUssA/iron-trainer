---
# iron-trainer-1k4b
title: 'backend-v2: BootUI developer console for local dev mode'
status: completed
type: task
priority: normal
created_at: 2026-07-20T19:14:35Z
updated_at: 2026-07-21T06:13:06Z
---

Add the BootUI Quarkus extension (com.julien-dubois.bootui:bootui-quarkus:1.12.0, targets Quarkus 3.37.x) for a richer dev/test-mode console at http://localhost:8080/bootui. Dev/test-only, dark in production. Requested by Viktor via https://www.julien-dubois.com/boot-ui/setup.

## Todos
- [x] Verify artifact resolves from Central + Quarkus 3.37 compat
- [x] Add dependency to backend-v2/pom.xml
- [x] Verify /bootui loads in quarkus:dev
- [ ] PR + CI (confirm prod/native build unaffected) + merge

## Summary of Changes 2026-07-21
BootUI dev console added (bootui-quarkus 1.12.0, dev/test-only) — PR #86 merged, CI incl native green, /bootui verified in quarkus:dev + dark in prod. (z8b5 was a duplicate of this, scrapped.)
