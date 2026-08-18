---
# iron-trainer-bec4
title: Docs for a non-technical athlete
status: todo
type: epic
priority: normal
created_at: 2026-08-18T16:16:12Z
updated_at: 2026-08-18T16:16:46Z
parent: iron-trainer-sgfg
blocked_by:
    - iron-trainer-thuc
---

The audience is an age-group triathlete who can install Docker Desktop and follow
numbered steps. They are not developers. Every instruction that assumes a terminal
habit is a place they give up.

## Todo
- [ ] Quickstart README aimed at that reader: install Docker Desktop, download one
      file, run one command, open a browser. Screenshots, not prose
- [ ] **Strava API app walkthrough** — the hardest step by far, and unavoidable if
      they want live sync. Register an app at strava.com/settings/api, set the
      callback domain to `localhost`, copy two values. Needs screenshots and an
      explicit "this is free and takes 2 minutes"
- [ ] Say plainly what works without any keys at all (archive import, plan
      generation with the deterministic planner, all charts) versus what needs
      Strava (live sync) or Anthropic (AI plan adaptation)
- [ ] Troubleshooting: port 8080 already in use, Docker not running, out of disk,
      "it says no plan yet", how to read `docker compose logs`
- [ ] How to update, and how to back up first
- [ ] Set expectations on the iOS app: it pairs to a server over the network, so a
      laptop install needs the phone on the same LAN and the host reachable — this
      will not work the way the SaaS does
