---
# iron-trainer-1k4b
title: 'backend-v2: BootUI developer console for local dev mode'
status: in-progress
type: task
created_at: 2026-07-20T19:14:35Z
updated_at: 2026-07-20T19:14:35Z
---

Add the BootUI Quarkus extension (com.julien-dubois.bootui:bootui-quarkus:1.12.0, targets Quarkus 3.37.x) for a richer dev/test-mode console at http://localhost:8080/bootui. Dev/test-only, dark in production. Requested by Viktor via https://www.julien-dubois.com/boot-ui/setup.

## Todos
- [x] Verify artifact resolves from Central + Quarkus 3.37 compat
- [x] Add dependency to backend-v2/pom.xml
- [x] Verify /bootui loads in quarkus:dev
- [ ] PR + CI (confirm prod/native build unaffected) + merge
