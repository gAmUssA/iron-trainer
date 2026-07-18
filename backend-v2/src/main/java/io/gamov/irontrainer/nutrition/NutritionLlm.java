package io.gamov.irontrainer.nutrition;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Guarded wrapper over {@link NutritionAi} — the seam that keeps the LLM
 * OPTIONAL. Port of planning/llm's LLMUnavailable pattern: when no API key is
 * configured (dev/test/parity, or prod before the key is wired), or the model
 * call fails, callers get {@link Unavailable} and fall back to the deterministic
 * plan. Converts the structured {@link NutritionAi.Timeline} to the snake_case
 * item maps the rest of the nutrition code (applyLlmTimeline) works with. */
@ApplicationScoped
public class NutritionLlm {

    @Inject
    NutritionAi ai;

    // The sentinel the extension is given when ANTHROPIC_API_KEY is unset (see
    // application.properties) — the extension needs a non-empty value to boot,
    // but this one means "no real key".
    static final String NO_KEY = "no-key";

    // Same property the langchain4j Anthropic extension reads.
    @ConfigProperty(name = "quarkus.langchain4j.anthropic.api-key")
    Optional<String> apiKey;

    /** Mirrors planning/llm.LLMUnavailable — the model can't be used; fall back. */
    public static final class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }

    /** anthropic_configured: a REAL API key is present (not blank, not the boot
     * sentinel). */
    public boolean available() {
        return apiKey.map(k -> !k.isBlank() && !k.equals(NO_KEY)).orElse(false);
    }

    public record Result(String summary, List<Map<String, Object>> items) {}

    /** Ask the model for a race-day timeline. Args are JSON strings (profile,
     * race, readiness, deterministic prior). Throws {@link Unavailable} when no
     * key is set or the call fails. */
    public Result generate(String profileJson, String raceJson, String readinessJson, String priorJson) {
        if (!available()) {
            throw new Unavailable("ANTHROPIC_API_KEY not set.");
        }
        NutritionAi.Timeline t;
        try {
            t = ai.generate(profileJson, raceJson, readinessJson, priorJson);
        } catch (RuntimeException e) {
            throw new Unavailable(e.toString());
        }
        // A null timeline (structured-output parse produced nothing rather than
        // throwing) must ALSO fall back — otherwise t.items() below NPEs OUTSIDE
        // the caller's Unavailable catch and the endpoint 500s instead of
        // serving the deterministic plan.
        if (t == null) {
            throw new Unavailable("Model returned no timeline.");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        if (t.items() != null) {
            for (NutritionAi.Item i : t.items()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("phase", i.phase());
                m.put("offset_min", i.offsetMin());
                m.put("label", i.label());
                m.put("carbs_g", i.carbsG());
                m.put("fluid_ml", i.fluidMl());
                m.put("sodium_mg", i.sodiumMg());
                m.put("notes", i.notes());
                items.add(m);
            }
        }
        return new Result(t.summary(), items);
    }
}
