package io.gamov.irontrainer.nutrition;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import java.util.List;

/** LangChain4j AI service for race-day nutrition — port of
 * planning/llm.generate_race_day_nutrition. The deterministic targets are the
 * safety prior; the model returns a concrete timeline that the caller re-clamps
 * via Nutrition.validateFueling. Structured output → {@link Timeline}. The last
 * prompt sentence differs from FastAPI ("Output the timeline as structured JSON"
 * vs "Output via the tool") because LangChain4j uses structured output, not
 * Anthropic tool-use — the timeline shape and safety clamps are unchanged. */
@RegisterAiService
public interface NutritionAi {

    @SystemMessage("""
            You are an expert sports nutritionist for IRONMAN 70.3 and 140.6 racing. \
            You are given deterministic, physiology-based fueling targets (carbs, \
            fluid, sodium per leg) computed from the athlete's body weight, projected \
            splits and the research literature. Treat those numbers as a firm prior: \
            produce a concrete timeline (pre-race meal, pre-race snack, swim, T1, bike, \
            T2, run, recovery) with specific times (offset_min relative to the swim \
            start, negative before the gun), real product suggestions, and amounts. \
            Stay within the given per-hour rates — never exceed 120 g carbs/h or \
            1000 mL/h, and use a glucose:fructose blend above 60 g/h. Output the \
            timeline as structured JSON.""")
    @UserMessage("""
            Athlete profile: {profile}
            Race: {race}
            Projected splits / readiness: {readiness}

            Deterministic fueling prior to follow:
            {prior}

            Return a concrete race-day fueling timeline.""")
    Timeline generate(String profile, String race, String readiness, String prior);

    /** The LLM's timeline. `phase` ∈ pre_race/swim/t1/bike/t2/run/post_race;
     * offset_min is relative to the swim start (negative before the gun). */
    record Timeline(String summary, List<Item> items) {}

    record Item(String phase, Integer offsetMin, String label,
                Integer carbsG, Integer fluidMl, Integer sodiumMg, String notes) {}
}
