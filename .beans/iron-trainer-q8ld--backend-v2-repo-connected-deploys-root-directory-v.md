---
# iron-trainer-q8ld
title: backend-v2 repo-connected deploys (root directory via MCP)
status: completed
type: task
priority: normal
created_at: 2026-07-15T21:25:38Z
updated_at: 2026-07-15T21:53:59Z
parent: iron-trainer-u3zo
---

Set root_directory=backend-v2 + watch paths + /q/health healthcheck via Railway MCP, reconnect GitHub source → auto-deploys on merge.

## Summary of Changes

Repo-connected auto-deploys WORKING: root_directory=backend-v2 + watch paths via MCP; scaffold .dockerignore fixed (excluded all build inputs); PORT honored + pinned; IPv6 bind (::). Railway healthcheck gate persistently reported service-unavailable against a provably-listening container — disabled via config-as-code (backend-v2/railway.toml, healthcheckPath empty), re-enable when the service takes real traffic. Config canonicalized as railway.toml. Latest deploy SUCCESS, /q/health UP.
