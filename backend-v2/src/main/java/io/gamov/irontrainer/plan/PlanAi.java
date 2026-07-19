package io.gamov.irontrainer.plan;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import java.util.List;

/** LangChain4j AI service for season adaptation — port of
 * planning/llm.adjust_season. Adapts a structurally-valid skeleton to the
 * athlete's real fitness; the deterministic validator re-clamps the result, so
 * the model can only move things within safe bounds. Structured output →
 * {@link Season}. (The last prompt line differs from FastAPI — structured output
 * vs Anthropic tool-use — but the shape and safety clamps are unchanged.) */
@RegisterAiService
public interface PlanAi {

    @SystemMessage("""
            You are an expert triathlon coach specializing in IRONMAN 70.3. You \
            adapt a structurally-valid plan skeleton to the athlete's real fitness. \
            Keep the same weeks and week_start dates. Adjust focus, target_hours and \
            is_recovery to suit the athlete. Respect a progressive build with recovery \
            weeks and a 2-week taper. Output the adjusted season as structured JSON.""")
    @UserMessage("""
            Athlete thresholds: {profile}
            Athlete HR zones: {zones}
            Recent fitness (CTL/ATL/TSB & weekly volume): {fitness}

            Starting plan skeleton to adapt:
            {skeleton}

            Return an adjusted season for this specific athlete.""")
    Season adjust(String profile, String zones, String fitness, String skeleton);

    /** The adapted season. Week fields mirror the template skeleton; the caller
     * maps them back to the snake_case season the validator/expander consume. */
    record Season(String summary, List<Week> weeks) {}

    record Week(Integer weekIndex, String weekStart, String phase, Boolean isRecovery,
                String focus, Double targetHours, Double targetTss) {}
}
