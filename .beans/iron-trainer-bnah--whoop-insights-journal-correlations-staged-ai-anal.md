---
# iron-trainer-bnah
title: 'WHOOP insights: journal correlations + staged AI analysis (Steve Tan method)'
status: todo
type: feature
created_at: 2026-08-05T00:18:31Z
updated_at: 2026-08-05T00:18:31Z
parent: iron-trainer-ids6
---

From stevetan.com/resources/whoop-claude-health-analyst (PDF also in iCloud Downloads): a 5-stage Claude prompt over the raw WHOOP export that surfaces what the app never shows. Now that whoop_cycles is in prod (bean 48hy), the transferable ideas:

1. **journal_entries.csv is the unique unlock** — we currently ignore it. Behavioral correlations: alcohol → next-day HRV/recovery (quantified per-athlete), late meals → elevated RHR, caffeine timing → sleep. Needs a whoop_journal table (cycle date + question + yes/no) and a correlation pass vs whoop_cycles.
2. **Bedtime variance** — often costs more recovery than total sleep hours. We store cycle_start (≈ sleep onset); compute variance trend, compare vs sleep_performance_pct.
3. **Strain-to-recovery ratio trend** (28d) — is load outpacing recovery direction-of-travel.
4. **Staged AI analysis** — backend already has langchain4j-anthropic (plan generation). "Analyze my WHOOP data" button: Stage 1 inventory/gaps → 2 personal baselines → 3 pattern/correlation analysis → 4 top-5 ranked recovery damagers → 5 weekly protocol. Render as an insights card on the WHOOP tab.

## Todo
- [ ] Import journal_entries.csv (whoop_journal table, header-tolerant like WhoopArchive)
- [ ] Correlation endpoint: journal behaviors vs next-day recovery/HRV deltas
- [ ] Bedtime-variance + strain:recovery ratio series
- [ ] AI insights: staged prompt over cycles+journal via existing anthropic integration
