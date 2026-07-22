---
# iron-trainer-4uj1
title: 'Reverse account link: connect Strava to an Apple-first account'
status: todo
type: feature
priority: normal
created_at: 2026-07-22T21:59:37Z
updated_at: 2026-07-22T21:59:37Z
---

Complements [[iron-trainer-3e6w]] (Sign in with Apple). The Apple→link direction is done (AppleResource links the Apple id to the current authenticated athlete). This is the REVERSE: an Apple-first user connects Strava → link stravaAthleteId to their CURRENT athlete instead of find-or-create-new.

Where: StravaResource callback `persistLogin` currently always find-or-creates by stravaId. Change: before find-or-create, if the OAuth session carries a current athlete_id (a logged-in Apple user, preserved through /connect) whose athlete has no stravaAthleteId AND this stravaId is free → attach stravaId + tokens to that athlete (link).

Deferred from 3e6w because StravaResource is parity-sensitive and there's an in-flight feature/strava-oauth worktree — do this carefully to avoid conflicts. Out of scope: merging two pre-existing accounts.
