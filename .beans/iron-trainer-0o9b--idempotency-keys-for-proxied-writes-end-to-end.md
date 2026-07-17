---
# iron-trainer-0o9b
title: idempotency keys for proxied writes (end-to-end)
status: todo
type: task
created_at: 2026-07-17T05:27:36Z
updated_at: 2026-07-17T05:27:36Z
parent: iron-trainer-eom4
---

From PR #56 review: when a proxied write is DELIVERED but its response is lost (read timeout / protocol error), the strangler returns 502 and does not retry — but a client that auto-retries on 5xx can still double-apply a non-idempotent mutation. Closing this fully needs idempotency keys end-to-end: client sends Idempotency-Key (already forwarded by the strangler now), backend-v2 dedupes on it. Deferred; the 502-no-local-retry already prevents server-side double-apply.
