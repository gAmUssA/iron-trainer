---
# iron-trainer-9q75
title: 'Export routing flip: FastAPI proxies bearer export traffic to backend-v2'
status: todo
type: task
created_at: 2026-07-15T21:25:38Z
updated_at: 2026-07-15T21:25:38Z
parent: iron-trainer-u3zo
---

Thin reverse-proxy in export_router: bearer-authenticated export requests forward to EXPORT_PROXY_URL (backend-v2 over Railway private network); session-cookie requests stay Python-served. Env kill-switch = instant rollback.
