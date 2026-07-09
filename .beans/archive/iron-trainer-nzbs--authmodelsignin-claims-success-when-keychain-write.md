---
# iron-trainer-nzbs
title: AuthModel.signIn claims success when Keychain write fails
status: scrapped
type: bug
priority: low
created_at: 2026-07-08T17:29:56Z
updated_at: 2026-07-09T14:43:14Z
---

Surfaced during PR #14 sim testing (unsigned builds): Keychain.set failure is swallowed and isSignedIn goes true with no stored bearer → 'Load my plan' silently no-ops. Realistic only on unsigned/dev builds, but signIn should verify the write. Noted in ADR 0012.

## Reasons for Scrapping

Only observable on unsigned dev builds (simulator/CODE_SIGNING_ALLOWED=NO), where the Keychain rejects writes — signed TestFlight/App Store builds are unaffected. Documented in ADR 0012 as a known edge. Not worth a TestFlight cycle; if a real device ever hits it, the Settings 'Widget data' diagnostic + re-pairing recovers, and this bean can be resurrected from the archive.
