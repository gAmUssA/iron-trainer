---
# iron-trainer-nzbs
title: AuthModel.signIn claims success when Keychain write fails
status: todo
type: bug
priority: low
created_at: 2026-07-08T17:29:56Z
updated_at: 2026-07-08T17:29:56Z
---

Surfaced during PR #14 sim testing (unsigned builds): Keychain.set failure is swallowed and isSignedIn goes true with no stored bearer → 'Load my plan' silently no-ops. Realistic only on unsigned/dev builds, but signIn should verify the write. Noted in ADR 0012.
