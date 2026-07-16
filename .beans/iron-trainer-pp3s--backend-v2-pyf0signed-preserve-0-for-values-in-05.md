---
# iron-trainer-pp3s
title: 'backend-v2 Py.f0signed: preserve ''-0'' for values in (-0.5, 0)'
status: todo
type: bug
priority: normal
created_at: 2026-07-16T02:35:03Z
updated_at: 2026-07-16T02:35:03Z
---

Py.f0signed returns '+0' for x in (-0.5, 0) (e.g. tsb=-0.4 rounds to 0, no leading '-', prepends '+'), but Python f-string {x:+.0f} returns '-0'. Latent char-for-char parity deviation. Unreachable today: both call sites guarded (tsb<-25, tsb>10). Fix: detect x<0 && rounded==0 -> '-0'; add Py unit tests f0signed(-0.4)=='-0', f0signed(0.0)=='+0', f0signed(-0.6)=='-1'. Source: code-review of feature/proxy-routing (Py.java:27, CONFIRMED).
