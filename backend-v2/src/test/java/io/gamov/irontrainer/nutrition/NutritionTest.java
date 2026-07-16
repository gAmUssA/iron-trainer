package io.gamov.irontrainer.nutrition;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
