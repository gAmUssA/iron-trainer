---
# iron-trainer-04cc
title: No HTTP security headers on a browser-facing app
status: todo
type: task
priority: normal
created_at: 2026-08-21T14:11:47Z
updated_at: 2026-08-21T14:11:47Z
---

BootUI's security and pentest advisors both flag it, and it checks out: the app
configures **no HTTP security headers at all** (`grep -c quarkus.http.header
application.properties` = 0).

That matters here because backend-v2 is not an API — it serves the React SPA to
browsers as the single front door, on a public origin.

Missing, in rough order of value:

| Header | Why it matters here |
|---|---|
| `X-Content-Type-Options: nosniff` | one line, no downside, stops MIME-sniffing |
| `X-Frame-Options: DENY` (or CSP `frame-ancestors 'none'`) | the app has no legitimate embedder; without it, clickjacking |
| `Strict-Transport-Security` | Railway terminates TLS. Directly related to the /privacy scheme-downgrade bug (PR #128) — HSTS would have made that bug harmless |
| `Referrer-Policy: strict-origin-when-cross-origin` | OAuth callbacks carry `code`/`state` in the query string; the default policy can leak a full callback URL to a third-party link |
| `Content-Security-Policy` | most valuable and most work — needs tailoring to the Vite bundle's script/style origins, so do it last and test the SPA still renders |

## Notes
- The pentest probe ran over local HTTP, so its HSTS result is inconclusive by
  construction; the config scan is the authoritative one and says the header is
  simply absent.
- Set these via `quarkus.http.header."<name>".value` so they apply to the static
  SPA responses too, not just JAX-RS.
- Referrer-Policy is the one with a concrete threat model in this app rather than
  a generic hardening argument — OAuth `code` in a URL is exactly what it protects.

## Todo
- [ ] Add nosniff, X-Frame-Options, Referrer-Policy, HSTS
- [ ] CSP last, tailored to the built SPA; verify the app still renders and the
      privacy page still loads
- [ ] Re-run BootUI security_scan + pentest_scan to confirm they clear
