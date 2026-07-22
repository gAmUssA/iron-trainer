---
# iron-trainer-p15n
title: 'Research/spike: move auth to Supabase Auth (GoTrue)'
status: todo
type: task
priority: low
created_at: 2026-07-22T23:08:09Z
updated_at: 2026-07-22T23:08:09Z
---

Evaluate migrating ALL auth from the custom Quarkus backend to Supabase Auth (GoTrue) — supabase-js on the frontend, RLS for tenancy, auth.users as the single identity — instead of the current custom stack (athlete table + custom Strava OAuth + itsdangerous session cookies + device bearers).

Context (2026-07-22): considered while adding Sign in with Apple ([[iron-trainer-3e6w]]). Decided NOT to use Supabase's Apple provider for SIWA because it's a PARALLEL identity system (auth.users vs our athlete), Strava has no Supabase provider (so we can't consolidate), and it adds an Apple private-key requirement our direct id_token verify avoids. Supabase is currently ONLY managed Postgres (auth/realtime/storage off).

Research scope:
- Can Supabase Auth model our multi-tenant athlete + Strava-OAuth + Apple + device-pairing needs? Strava as a custom OIDC/OAuth provider in GoTrue? Device (iOS) bearer flow?
- Migration cost: rewrite StravaResource login, SessionCookie, Devices, the athlete↔user bridge, RLS policies, and the iOS pairing/bearer path. Data migration for existing athletes.
- Payoff: managed auth (refresh, MFA, providers), less custom crypto — vs the churn + a Supabase-Auth lock-in.
- Verdict + a phased plan if worthwhile. Big rewrite — likely not worth it unless auth needs grow.
