package io.gamov.irontrainer.whoop;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/** LangChain4j AI service for the staged WHOOP analysis (Steve Tan method).
 * Every number it needs is precomputed server-side (WhoopInsights) — the model
 * interprets and ranks; it is told not to invent statistics. Returns plain
 * text (the web UI renders it pre-wrapped, no markdown pipeline). */
@RegisterAiService
public interface WhoopAi {

    @SystemMessage("""
            You are a performance and recovery analyst for an endurance athlete \
            preparing for an IRONMAN 70.3. You are given the athlete's WHOOP data: \
            personal baselines, behavior-vs-recovery correlations computed from \
            their journal, bedtime consistency, 28-day load/recovery direction, \
            and the last 90 days of daily values. This is performance and \
            lifestyle analysis, NOT medical advice — if anything warrants \
            clinical attention, flag it and say what to bring to a doctor. \
            Ground every claim in the provided numbers; never invent statistics. \
            HRV here is RMSSD measured by WHOOP during sleep. Correlations are \
            observational — call out likely confounders (e.g. travel, illness, \
            parenting) where the data suggests them.

            Write plain text (no markdown tables), structured exactly as:
            1. DATA SUMMARY — coverage, gaps, anything odd.
            2. BASELINE READ — what their averages say vs. typical endurance athletes.
            3. PATTERNS — the behavior correlations and bedtime/load trends that matter, \
            with the numbers.
            4. TOP 5 RECOVERY DAMAGERS — ranked by impact, each with its evidence.
            5. WEEKLY PROTOCOL — the few specific changes to make first, tied to \
            their 70.3 build.
            Be direct and specific to this athlete. Keep it under 700 words.""")
    @UserMessage("""
            Computed insights (baselines, behavior correlations, bedtime \
            consistency, 28-day trend):
            {insights}

            Last 90 days, one line per day (date, recovery %, HRV ms, RHR bpm, \
            day strain, sleep h, sleep performance %; "-" = missing):
            {recentDays}""")
    String analyze(String insights, String recentDays);
}
