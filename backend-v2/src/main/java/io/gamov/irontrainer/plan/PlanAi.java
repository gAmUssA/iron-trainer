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

    /** Design one week of concrete structured workouts — port of
     * planning/llm.generate_week_workouts. The validator caps + fueling run after,
     * and the deterministic template is the fallback. Structured output →
     * {@link WeekWorkouts}. */
    @SystemMessage("""
            You are an expert IRONMAN 70.3 coach. Design one week of concrete, \
            structured swim/bike/run workouts with warmup/main/cooldown steps and \
            explicit power (W), pace (sec/km or sec/100m) or HR targets derived from \
            the athlete's thresholds. Name the HR zone in the description (e.g. 'Z2 \
            endurance'). Total duration should match the week's target_hours. Adapt to \
            the athlete's current state in the context. Output the workouts as \
            structured JSON.""")
    @UserMessage("""
            Week skeleton: {week}
            Athlete thresholds: {profile}
            Context (fitness / feel): {context}

            Return this week's concrete workouts.""")
    WeekWorkouts generateWeek(String week, String profile, String context);

    record WeekWorkouts(List<Workout> workouts) {}

    record Workout(String date, String sport, String title, String description, String intensity,
                   Integer durationSec, Double distanceM, Double plannedTss, List<Step> steps) {}

    record Step(String type, Integer durationSec, Target target) {}

    /** Step target: power/pace → low/high numeric; hr → bpm band; open → nulls. */
    record Target(String type, String unit, Integer low, Integer high) {}
}
