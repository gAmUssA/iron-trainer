---
# iron-trainer-bnah
title: 'WHOOP insights: journal correlations + staged AI analysis (Steve Tan method)'
status: completed
type: feature
priority: normal
created_at: 2026-08-05T00:18:31Z
updated_at: 2026-08-05T00:36:41Z
parent: iron-trainer-ids6
---

From stevetan.com/resources/whoop-claude-health-analyst (PDF also in iCloud Downloads): a 5-stage Claude prompt over the raw WHOOP export that surfaces what the app never shows. Now that whoop_cycles is in prod (bean 48hy), the transferable ideas:

1. **journal_entries.csv is the unique unlock** — we currently ignore it. Behavioral correlations: alcohol → next-day HRV/recovery (quantified per-athlete), late meals → elevated RHR, caffeine timing → sleep. Needs a whoop_journal table (cycle date + question + yes/no) and a correlation pass vs whoop_cycles.
2. **Bedtime variance** — often costs more recovery than total sleep hours. We store cycle_start (≈ sleep onset); compute variance trend, compare vs sleep_performance_pct.
3. **Strain-to-recovery ratio trend** (28d) — is load outpacing recovery direction-of-travel.
4. **Staged AI analysis** — backend already has langchain4j-anthropic (plan generation). "Analyze my WHOOP data" button: Stage 1 inventory/gaps → 2 personal baselines → 3 pattern/correlation analysis → 4 top-5 ranked recovery damagers → 5 weekly protocol. Render as an insights card on the WHOOP tab.

## Todo
- [x] Import journal_entries.csv (whoop_journal, V5; joined to cycles by shared Cycle start time → wake date)
- [x] GET /api/whoop/insights: same-day behavior deltas (≥5 days/side, top 10 by |Δrecovery|), computed in Java (WhoopInsights)
- [x] Bedtime consistency (circular stddev, 28d vs all) + 28d-vs-prev-28d strain/recovery/HRV direction
- [x] POST /api/whoop/insights/analyze (async job, WhoopAi 5-stage prompt, persisted in whoop_insight) + UI cards

## Summary of Changes

ADR 0054. V5 (whoop_journal + whoop_insight), WhoopInsights (deterministic stats — LLM narrates, never computes), WhoopAi staged prompt (performance-not-medical, confounder-aware), insights/analyze endpoints, 3 new UI sections on WHOOP tab. Tests: +6 (WhoopInsightsTest 4, journal parse 2); suite 223 green. Validated with real export: 11,509 journal answers; analysis correctly flagged 90d HRV depression (29.8 vs 40.6 ms), +5bpm RHR, ±76min bedtime spread. Deferred: next-day lag correlations, per-cycle timezone storage, analysis history.

**2026-08-05 follow-up:** AI analysis rate-limited to 2 runs/athlete/UTC-day (runs_date/runs_count on whoop_insight, charged before the paid call; analyze_runs_left in GET /insights, UI disables at 0).
