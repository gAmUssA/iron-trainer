package io.gamov.irontrainer.plan;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Guarded wrapper over {@link PlanAi} — the seam that keeps the season LLM
 * OPTIONAL (same pattern as NutritionLlm/fd31). When no API key is configured,
 * or the model call fails, callers get {@link Unavailable} and fall back to the
 * deterministic template season. Converts the structured {@link PlanAi.Season}
 * back to the snake_case season map the validator + expander consume. */
@ApplicationScoped
public class PlanLlm {

    @Inject
    PlanAi ai;

    @ConfigProperty(name = "quarkus.langchain4j.anthropic.api-key")
    Optional<String> apiKey;

    static final String NO_KEY = "no-key";

    public static final class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }

    public boolean available() {
        return apiKey.map(k -> !k.isBlank() && !k.equals(NO_KEY)).orElse(false);
    }

    /** adjust_season: adapt the template season to the athlete. Throws
     * {@link Unavailable} when no key is set or the call fails; the merged season
     * keeps the template's weeks/summary when the model omits them. */
    public Map<String, Object> adjustSeason(Map<String, Object> templateSeason,
                                            String profileJson, String zonesJson, String fitnessJson) {
        if (!available()) {
            throw new Unavailable("ANTHROPIC_API_KEY not set.");
        }
        PlanAi.Season t;
        try {
            t = ai.adjust(profileJson, zonesJson, fitnessJson, io.gamov.irontrainer.util.PyJson.dumps(templateSeason));
        } catch (RuntimeException e) {
            throw new Unavailable(e.toString());
        }
        if (t == null) {
            throw new Unavailable("Model returned no season.");
        }
        Map<String, Object> merged = new LinkedHashMap<>(templateSeason);
        // Python out.get("summary", template): a present value (even "") overrides;
        // only a missing/null summary keeps the template's.
        if (t.summary() != null) {
            merged.put("summary", t.summary());
        }
        List<Map<String, Object>> weeks = convertWeeks(t.weeks());
        if (!weeks.isEmpty()) {
            // Guard the never-500 contract: structured output doesn't hard-enforce
            // required fields, so a week with a null/blank week_start would NPE
            // later in expandWeek's LocalDate.parse (outside the Unavailable catch).
            // Treat a malformed LLM season as unavailable → deterministic template.
            for (Map<String, Object> w : weeks) {
                Object ws = w.get("week_start");
                if (ws == null || String.valueOf(ws).isBlank()) {
                    throw new Unavailable("LLM season week missing week_start.");
                }
            }
            merged.put("weeks", weeks);
        }
        return merged;
    }

    private static List<Map<String, Object>> convertWeeks(List<PlanAi.Week> weeks) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (weeks == null) {
            return out;
        }
        for (PlanAi.Week w : weeks) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("week_index", w.weekIndex());
            m.put("week_start", w.weekStart());
            m.put("phase", w.phase());
            m.put("is_recovery", w.isRecovery());
            m.put("focus", w.focus());
            m.put("target_hours", w.targetHours());
            m.put("target_tss", w.targetTss());
            out.add(m);
        }
        return out;
    }
}
