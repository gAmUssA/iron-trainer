---
# iron-trainer-x8t8
title: Retire itsdangerous/Starlette cookie scheme for a modern signed cookie
status: todo
type: feature
priority: deferred
created_at: 2026-08-08T23:24:43Z
updated_at: 2026-08-08T23:24:43Z
parent: iron-trainer-y2yz
---

SessionCookie.java reproduces itsdangerous.Signer (HMAC-SHA1, django-concat key derivation, salt) to verify browser cookies FastAPI signed. backend-v2 now both mints AND verifies (strangle window over, ADR 0020/0022).

## Why DEFER (HIGH risk, live sessions)
Real 14-day-max-age browser cookies were signed by FastAPI with this scheme; can't just switch.
- [ ] Move to a modern signed cookie / JWT with a dual-verify transition window (accept old itsdangerous cookies until the 14-day max-age expires) OR a forced re-login.
- [ ] Then delete the itsdangerous verify path + its PyJson usage.

## Notes
Independent of the rest, lowest priority. NOTE: the PyJson.dumps inside sign() is already safe to compact via child  (self-consistent); this bean is only about replacing the SCHEME.
