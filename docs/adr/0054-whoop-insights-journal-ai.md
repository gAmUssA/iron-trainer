# 0054 — WHOOP insights: journal correlations + staged AI analysis (2026-08-05)

- **Status:** Accepted
- **Beans:** bnah (this feature), under epic ids6; builds on 48hy / ADR 0053

## Context

The WHOOP tab (ADR 0053) charts scores but explains nothing. Steve Tan's
"WHOOP + Claude Health Analyst" method (stevetan.com) showed the export holds
answers the WHOOP app never surfaces — behavior→recovery correlations from the
journal, bedtime-variance cost, load-vs-recovery direction — and that a staged
LLM pass turns them into a ranked, actionable read.

## Decision

**1. journal_entries.csv now imports** into `whoop_journal` (V6, PK
`athlete_id+date+question`, idempotent like cycles). Journal rows join their
cycle **by the shared `Cycle start time`** and inherit its wake-date — an
end-date heuristic would shift after-midnight bedtimes (exactly the nights
under study) one day forward. Sleeps/workouts stay excluded (HealthKit
double-count, ADR 0053).

**2. All statistics are computed deterministically in Java** (`WhoopInsights`,
pure static, unit-tested); the LLM narrates numbers it is handed and is
prompt-forbidden from inventing statistics. Computed: per-question same-day
recovery/HRV deltas (≥5 scored days on each side, ranked by |Δrecovery|, top
10), bedtime consistency as **circular** std-dev of cycle-start time-of-day
(23:30 vs 00:30 = 60 min, not 23 h; UTC times — a constant home-offset cancels
out of a spread, travel weeks inflate it by design), and 28-day vs
previous-28-day means for strain/recovery/HRV. Windows anchor on the newest
data date, not "today", so a stale export analyzes its own last 28 days.

**3. Staged AI analysis mirrors the nutrition LLM pattern**: `@RegisterAiService
WhoopAi` (plain-text output — the frontend has no markdown pipeline),
`?async=1` job (kind `whoop_insights`) because the call runs ~30–60 s, same
`no-key` availability sentinel. Prompt = computed insights JSON + last-90-days
compact CSV; output fixed to five stages (data summary → baseline read →
patterns → top-5 recovery damagers → weekly protocol), explicitly
performance-not-medical with a see-a-doctor flag, told to call out
confounders. Result persists in `whoop_insight` (one row per athlete —
regenerate overwrites) so the paid, slow result survives reloads.
**Abuse guard:** analysis is capped at 2 runs per athlete per UTC day
(`runs_date`/`runs_count` on `whoop_insight`; gate order 429 → 503 → charge →
call, so a keyless env never burns a run and the slot is spent *before* the
paid call — retry-hammering can't multiply API cost). `GET /insights` exposes
`analyze_runs_left`; the UI swaps the button for a "try again tomorrow" note
at 0.

**4. UI**: three new sections on the WHOOP tab — Behavior Impact table
(colored Δrecovery/ΔHRV), Bedtime Consistency + 28-Day Direction cards, and
the AI Analysis card (button gated on `ai_available`, job-polled, pre-wrapped
text).

## Validation (real export, 2026-08-05)

2213 cycles + **11,509 journal answers** imported. Correlations produced real
signal (travel −5.2 pts, meat-day −7.7 pts flagged as probable
fueling-timing confound); the staged analysis correctly isolated the 90-day
HRV depression (29.8 vs 40.6 ms all-time) with +5 bpm RHR, the ±76 min
bedtime spread, an anomalous 1%-recovery night, and read the last-28-day
recovery uptick as deload adaptation.

## Consequences

- Correlations are observational and same-day only; no lag analysis (behavior
  → next-day) yet — add if a behavior plausibly acts with a 1-day delay.
- Bedtime variance uses UTC cycle starts; storing the per-cycle timezone would
  make travel weeks exact.
- One persisted analysis per athlete — no history. Fine until someone wants to
  compare runs.
- The AI card depends on `ANTHROPIC_API_KEY` in prod (already set for plan
  generation); job kind `whoop_insights` shows up in the existing jobs UI.
