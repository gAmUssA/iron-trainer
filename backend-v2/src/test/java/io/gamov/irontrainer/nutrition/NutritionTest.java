package io.gamov.irontrainer.nutrition;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mirrors backend/tests/test_nutrition.py (the ported subset). Values are the
 * Python spec's exact expectations; the endpoint/JSON-type parity is the gate. */
class NutritionTest {

    @Test
    void carbTargetPerHourBrackets() {
        assertEquals(0, Nutrition.carbTargetPerHour(30 * 60, "endurance"));   // 30 min
        assertEquals(0, Nutrition.carbTargetPerHour(44 * 60, "endurance"));
        assertEquals(30, Nutrition.carbTargetPerHour(60 * 60, "endurance"));
        assertEquals(45, Nutrition.carbTargetPerHour(90 * 60, "endurance"));
        assertEquals(60, Nutrition.carbTargetPerHour(2 * 3600, "endurance"));
        assertEquals(75, Nutrition.carbTargetPerHour((long) (2.75 * 3600), "endurance"));
        assertEquals(90, Nutrition.carbTargetPerHour(3 * 3600, "endurance"));
        assertEquals(90, Nutrition.carbTargetPerHour(5 * 3600, "endurance"));
    }

    @Test
    void carbTargetPerHourIntensity() {
        assertEquals(48, Nutrition.carbTargetPerHour(2 * 3600, "recovery"));   // 60 * 0.8
        assertEquals(99, Nutrition.carbTargetPerHour(3 * 3600, "threshold")); // 90 * 1.1 < 120
    }

    @Test
    void sweatRateEstimate() {
        assertEquals(0.84, Nutrition.estimateSweatRate(70, "endurance"), 1e-9);
        assertEquals(0.66, Nutrition.estimateSweatRate(55, "endurance"), 1e-9);
        assertEquals(1.02, Nutrition.estimateSweatRate(85, "endurance"), 1e-9);
        assertEquals(Nutrition.SWEAT_RATE_MIN_L_H, Nutrition.estimateSweatRate(20, "endurance"), 1e-9);
        // 200 * 0.012 * 1.3 = 3.12 → clamped to the 2.5 ceiling.
        assertEquals(Nutrition.SWEAT_RATE_MAX_L_H, Nutrition.estimateSweatRate(200, "vo2"), 1e-9);
    }

    @Test
    void hydrationSodiumGels() {
        assertEquals(672, Nutrition.hydrationTargetPerHour(0.84));  // 0.84 * 1000 * 0.8
        assertEquals(420, Nutrition.sodiumTargetPerHour(0.84));     // 0.84 * 500
        assertEquals(300, Nutrition.sodiumTargetPerHour(0.4));      // clamped to the 300 floor
        assertEquals(3, Nutrition.gelCount(60, 25));               // ceil(60/25)
        assertEquals(0, Nutrition.gelCount(0, 25));
    }

    @Test
    void dailyAndRecoveryTargets() {
        // weekly 8h → ~1.14 h/day → middle bracket (8 g/kg): 72 * 8 = 576.
        assertEquals(576, Nutrition.dailyCarbTarget(72, 8));
        assertEquals(360, Nutrition.dailyCarbTarget(72, 0));   // 0 h/day → 5 g/kg
        assertEquals(86, Nutrition.recoveryTarget(72));        // round(72 * 1.2)
    }

    @Test
    void workoutFuelingShortSessionNotNeeded() {
        Map<String, Object> out = Nutrition.computeWorkoutFueling(30 * 60, "endurance", null, 72.0, null);
        assertFalse((boolean) out.get("needed"));
        assertEquals(1800L, out.get("duration_s"));
        assertTrue(((String) out.get("note")).startsWith("Under 45 min"));
    }

    @Test
    void workoutFuelingFullWithEstimatedSweat() {
        // 2h endurance, weight set, no measured sweat → sweat estimated.
        Map<String, Object> out = Nutrition.computeWorkoutFueling(2 * 3600, "endurance", null, 70.0, null);
        assertTrue((boolean) out.get("needed"));
        assertEquals(60L, out.get("carb_g_h"));
        assertEquals(120L, out.get("carb_total_g"));           // 60 * 2h
        assertFalse((boolean) out.get("mtc_required"));        // 60 not > 60
        assertEquals(25.0, out.get("gel_carb_g"));             // default gel, echoed as float
        assertEquals(0.84, (double) out.get("sweat_rate_l_h"), 1e-9);
        assertEquals(672L, out.get("fluid_ml_h"));
        assertEquals(84L, out.get("recovery_carb_g"));         // round(70 * 1.2)
    }

    @Test
    void storedZeroSweatIsFalsyNotAbsent() {
        // Python: a stored sweat_rate_l_h of 0.0 is present-but-falsy — it does
        // NOT trigger estimate_sweat_rate (that's only for None), and `if sweat`
        // is false, so hydration/sodium/sweat_rate all emit null even though
        // body weight is set. (Not reachable via the API, but must stay faithful.)
        Map<String, Object> out = Nutrition.computeWorkoutFueling(2 * 3600, "endurance", null, 70.0, 0.0);
        assertNull(out.get("sweat_rate_l_h"));
        assertNull(out.get("fluid_ml_h"));
        assertNull(out.get("fluid_total_ml"));
        assertNull(out.get("sodium_mg_h"));
        assertNull(out.get("sodium_total_mg"));
        // carb targets still present; needed driven by carbs alone.
        assertEquals(60L, out.get("carb_g_h"));
        assertTrue((boolean) out.get("needed"));
    }

    @Test
    void dailyWithoutWeightIsNote() {
        Map<String, Object> out = Nutrition.computeDaily(null, 8.0);
        assertNull(out.get("body_weight_kg"));
        assertNull(out.get("daily_carb_g"));
        assertTrue(((String) out.get("note")).contains("Set body weight"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dailyWithWeight() {
        Map<String, Object> out = Nutrition.computeDaily(72.0, 8.0);
        assertEquals(72.0, out.get("body_weight_kg"));
        assertEquals(8.0, out.get("weekly_hours"));
        assertEquals(576L, out.get("daily_carb_g"));
        Map<String, Object> pre = (Map<String, Object>) out.get("pre_race");
        assertEquals(180L, pre.get("meal_3h_g"));   // round(72 * 2.5)
        assertEquals(72L, pre.get("snack_1h_g"));   // round(72 * 1.0)
        assertEquals(86L, out.get("recovery_carb_g"));
    }

    // ── Race-day plan ─────────────────────────────────────────────────────────

    /** Reference values captured from FastAPI compute_race_day_plan (70 kg, no
     * measured sweat, 70.3 with a 35/2:30/1:50 readiness projection). Locks the
     * banker's-rounding cases: round(187.5)=188, round(1212.5)=1212, round(82.5)=82. */
    @Test
    @SuppressWarnings("unchecked")
    void raceDayPlanMatchesPythonReference() {
        Map<String, Object> readiness = Map.of("legs", Map.of(
                "swim", Map.of("seconds", 2100L),
                "bike", Map.of("seconds", 9000L),
                "run", Map.of("seconds", 6600L)));
        Map<String, Object> race = Map.of(
                "name", "IRONMAN 70.3 New York", "date", "2026-09-26", "distance", "70.3");

        Map<String, Object> plan = Nutrition.computeRaceDayPlan(70.0, 25.0, null, race, readiness);

        assertEquals(25.0, plan.get("gel_carb_g"));
        assertFalse((boolean) plan.get("llm_used"));
        assertEquals(List.of(), plan.get("adjustments"));   // deterministic plan trips no cap
        assertEquals("~320 g carbs across the race (bike 75 g/h, run 45 g/h), fluid ~776 mL/h."
                + " Practice this in training — nothing new on race day.", plan.get("summary"));
        assertEquals(Map.of("name", "IRONMAN 70.3 New York", "date", "2026-09-26", "distance", "70.3"),
                plan.get("race"));

        List<Map<String, Object>> items = (List<Map<String, Object>>) plan.get("items");
        assertEquals(8, items.size());

        Map<String, Object> bike = items.get(4);
        assertEquals("bike", bike.get("phase"));
        assertEquals(40L, bike.get("offset_min"));
        assertEquals(9000L, bike.get("phase_duration_s"));
        assertEquals(188L, bike.get("carbs_g"));    // round(75 * 2.5) = round(187.5) HALF_EVEN → 188
        assertEquals(1940L, bike.get("fluid_ml"));  // round(776 * 2.5)
        assertEquals(1212L, bike.get("sodium_mg")); // round(485 * 2.5) = round(1212.5) → 1212
        assertEquals("75 g/h — one 25 g gel every 20 min (glucose:fructose blend above 60 g/h)."
                + " The bike is where most of the eating happens.", bike.get("notes"));

        Map<String, Object> run = items.get(6);
        assertEquals("run", run.get("phase"));
        assertEquals(193L, run.get("offset_min"));
        assertEquals(82L, run.get("carbs_g"));      // round(45 * 1.8333) = round(82.5) → 82
        assertEquals(889L, run.get("sodium_mg"));

        // Offsets that key off the projection: t1 after swim, t2/run/post after the bike.
        assertEquals(35L, items.get(3).get("offset_min"));   // t1 = round(2100/60)
        assertEquals(190L, items.get(5).get("offset_min"));  // t2 = round((2100+300+9000)/60)
        assertEquals(318L, items.get(7).get("offset_min"));  // post = 193 + round(110) + 15
        assertEquals(175L, items.get(0).get("carbs_g"));     // pre-race meal round(70 * 2.5)
    }

    /** No body weight → pre-race and recovery items omitted, an adjustment noted,
     * and no sweat estimate (so bike fluid/sodium stay null). */
    @Test
    @SuppressWarnings("unchecked")
    void raceDayWithoutWeightOmitsMealsAndHydration() {
        Map<String, Object> race = Map.of("name", "R", "date", "2026-09-26", "distance", "70.3");
        Map<String, Object> plan = Nutrition.computeRaceDayPlan(null, null, null, race, null);

        assertEquals(List.of("Body weight not set — pre-race and recovery amounts omitted."),
                plan.get("adjustments"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) plan.get("items");
        for (Map<String, Object> i : items) {
            assertNotEquals("pre_race", i.get("phase"));
            assertNotEquals("post_race", i.get("phase"));
            if ("bike".equals(i.get("phase"))) {
                assertNull(i.get("fluid_ml"));    // no weight → no sweat estimate → None
                assertNull(i.get("sodium_mg"));
            }
        }
        assertEquals(25.0, plan.get("gel_carb_g"));   // gel default when profile has none
    }

    /** validateFueling clamps a phase whose summed rate exceeds the ceiling —
     * the path the deterministic plan never reaches but the LLM overlay will. */
    // ── LLM overlay (apply_llm_timeline) ──────────────────────────────────────

    /** Overlay keeps each phase's base duration so validateFueling still
     * rate-checks, re-clamps the LLM amounts, and flips llm_used + summary.
     * Mirrors backend apply_llm_timeline. */
    @Test
    @SuppressWarnings("unchecked")
    void applyLlmTimelineOverlaysAndReclamps() {
        // Base: one bike hour carrying the phase duration the overlay must inherit.
        Map<String, Object> baseBike = new LinkedHashMap<>();
        baseBike.put("phase", "bike");
        baseBike.put("phase_duration_s", 3600L);
        baseBike.put("carbs_g", 75L);
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("items", new java.util.ArrayList<>(List.of(baseBike)));
        base.put("summary", "deterministic");
        base.put("llm_used", false);
        base.put("adjustments", List.of());

        // LLM item: no duration of its own, and an over-cap carb load.
        Map<String, Object> llmBike = new LinkedHashMap<>();
        llmBike.put("phase", "bike");
        llmBike.put("carbs_g", 500L);   // ≫ 120 g/h → must be clamped after duration is inherited
        llmBike.put("fluid_ml", 0L);
        llmBike.put("sodium_mg", 300L);
        llmBike.put("notes", "eat");

        Map<String, Object> plan = Nutrition.applyLlmTimeline(base, "llm summary", List.of(llmBike));

        assertTrue((boolean) plan.get("llm_used"));
        assertEquals("llm summary", plan.get("summary"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) plan.get("items");
        assertEquals(1, items.size());
        assertEquals(3600L, items.get(0).get("phase_duration_s"));  // inherited from base
        assertEquals(120L, items.get(0).get("carbs_g"));            // clamped to 120 g/h
        List<String> adj = (List<String>) plan.get("adjustments");
        assertTrue(adj.stream().anyMatch(n -> n.equals("bike: carbs capped at 120 g/h.")), adj.toString());
    }

    /** Empty LLM items → fall back to the base items (the `items or base`
     * branch); blank summary → base summary untouched; base is not mutated. */
    @Test
    @SuppressWarnings("unchecked")
    void applyLlmTimelineEmptyItemsKeepsBase() {
        Map<String, Object> baseBike = new LinkedHashMap<>();
        baseBike.put("phase", "bike");
        baseBike.put("phase_duration_s", 3600L);
        baseBike.put("carbs_g", 75L);
        List<Map<String, Object>> baseItems = new java.util.ArrayList<>(List.of(baseBike));
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("items", baseItems);
        base.put("summary", "deterministic");

        Map<String, Object> plan = Nutrition.applyLlmTimeline(base, "", List.of());

        // Items came from the base (validateFueling re-emits them, so it's a new
        // list, but the bike phase carried through with its 75 g/h load).
        List<Map<String, Object>> items = (List<Map<String, Object>>) plan.get("items");
        assertEquals(1, items.size());
        assertEquals("bike", items.get(0).get("phase"));
        assertEquals(75L, items.get(0).get("carbs_g"));
        assertEquals("deterministic", plan.get("summary")); // blank summary ignored
        assertTrue((boolean) plan.get("llm_used"));
        assertFalse(base.containsKey("llm_used"));           // base untouched
        assertFalse(baseBike.containsKey("notes"));          // base item untouched
    }

    @Test
    void validateFuelingClampsOverCap() {
        Map<String, Object> over = new LinkedHashMap<>();
        over.put("phase", "bike");
        over.put("carbs_g", 500L);        // 500 g in 1 h ≫ 120 g/h cap
        over.put("fluid_ml", 0L);
        over.put("sodium_mg", 300L);
        over.put("notes", "");
        over.put("phase_duration_s", 3600L);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("items", new java.util.ArrayList<>(List.of(over)));

        List<String> notes = Nutrition.validateFueling(plan);

        assertTrue(notes.stream().anyMatch(n -> n.equals("bike: carbs capped at 120 g/h.")), notes.toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) plan.get("items");
        assertEquals(120L, items.get(0).get("carbs_g"));   // round(500 * (120/500)) = 120
    }
}
