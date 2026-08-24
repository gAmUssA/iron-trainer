---
# iron-trainer-nruj
title: Expose Iron Trainer as a read-only MCP server
status: todo
type: feature
priority: normal
created_at: 2026-08-24T19:30:24Z
updated_at: 2026-08-24T19:30:24Z
---

An entire category in `docs/research/ai-triathlon-coaches-landscape.md` is athletes
hand-wiring Strava + WHOOP into Claude over MCP so they can *ask questions* of their
training data. Every one of them re-scrapes the sources to do it.

We already hold a better dataset than any of those setups — deduplicated activities,
PMC, readiness with ACWR, WHOOP recovery from the live API, Apple Health, the plan —
and there is no way to have a conversation with it. The dashboards answer the
questions we anticipated; nothing answers the ones we didn't.

This is the one item on the borrow list that is **ahead of** the field rather than
catching up: nobody in the survey exposes a curated, deduplicated dataset over MCP.

## Shape
A read-only MCP server over endpoints that already exist — PMC, readiness, WHOOP
cycles and insights, plan, activities, thresholds. Read-only on purpose: the value is
in asking, and a tool that can mutate the plan is a much larger safety conversation.

Worth having because a lot of this project's work already happens in Claude Code,
where the data currently is not reachable.

## Open questions
- Reuse the device bearer-token auth, or a separate scoped credential? Leaning
  separate: an MCP token that can only read is easy to reason about.
- Ship it in-process with the Quarkus app, or as a thin separate binary? In-process is
  less to run for a self-hoster, which matches the self-host epic.
- Does it belong in the self-host story at all, or is it a personal-workflow tool?

## Todo
- [ ] Pick the tool surface — start with PMC + readiness + WHOOP, resist exposing
      everything
- [ ] Decide auth
- [ ] Verify against real questions ("was my ramp too fast in March?"), not a checklist
