package io.gamov.irontrainer.nutrition;

import io.gamov.irontrainer.util.Py;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic fueling math — faithful port of app/nutrition.py (the subset
 * behind GET /api/nutrition/workout/{id} and /daily). Pure functions; JSON must
 * match FastAPI byte-for-byte, so every Python round() → Py.roundInt (emitted as
 * an integer, not 90.0) and round(x,2) → Py.round(x,2); math.ceil stays ceiling. */
public final class Nutrition {

    private Nutrition() {}

    static final double MIN_FUELED_DURATION_S = 45 * 60;   // 2700
    static final double DEFAULT_GEL_CARB_G = 25.0;
    static final double HIGH_CARB_GEL_G = 40.0;
    static final double MAX_CARB_G_H = 120.0;
    static final double MTC_THRESHOLD_G_H = 60.0;
    static final double BASE_SWEAT_L_H_PER_KG = 0.012;
    static final double SWEAT_RATE_MIN_L_H = 0.4;
    static final double SWEAT_RATE_MAX_L_H = 2.5;
    static final double FLUID_REPLACE_FRACTION = 0.8;
    static final double MAX_FLUID_ML_H = 1000.0;
    static final double SODIUM_MG_PER_L_SWEAT = 500.0;
    static final double SODIUM_MIN_MG_H = 300.0;
    static final double SODIUM_MAX_MG_H = 1000.0;
    static final double PRE_RACE_MEAL_G_KG = 2.5;
    static final double PRE_RACE_SNACK_G_KG = 1.0;
    static final double RECOVERY_G_KG = 1.2;

    // Upper bound of each bracket in hours -> g/h.
    static final double[][] CARB_BRACKETS = {
        {0.75, 0.0}, {1.25, 30.0}, {2.0, 45.0}, {2.5, 60.0}, {3.0, 75.0},
        {Double.POSITIVE_INFINITY, 90.0},
    };
    static final Map<String, Double> INTENSITY_SWEAT_FACTOR = Map.of(
        "recovery", 0.8, "endurance", 1.0, "tempo", 1.15,
        "threshold", 1.3, "vo2", 1.3, "test", 1.2);
    static final Map<String, Double> INTENSITY_CARB_FACTOR = Map.of(
        "recovery", 0.8, "endurance", 1.0, "tempo", 1.0,
        "threshold", 1.1, "vo2", 1.1, "test", 1.0);
    // g per kg body weight per day, by daily training hours (upper bound -> g/kg).
    static final double[][] DAILY_CARB_G_KG = {
        {1.0, 5.0}, {3.0, 8.0}, {Double.POSITIVE_INFINITY, 11.0},
    };

    // ── Core targets ─────────────────────────────────────────────────────────

    static long carbTargetPerHour(double durationS, String intensity) {
        double hours = Math.max(durationS, 0) / 3600.0;
        double base = 0.0;
        for (double[] b : CARB_BRACKETS) {
            if (hours < b[0]) { base = b[1]; break; }
        }
        double target = base * INTENSITY_CARB_FACTOR.getOrDefault(intensity, 1.0);
        return Py.roundInt(Math.min(target, MAX_CARB_G_H));
    }

    static boolean needsMtc(double carbGH) {
        return carbGH > MTC_THRESHOLD_G_H;
    }

    static double estimateSweatRate(double weightKg, String intensity) {
        double rate = weightKg * BASE_SWEAT_L_H_PER_KG
                * INTENSITY_SWEAT_FACTOR.getOrDefault(intensity, 1.0);
        return Py.round(Math.max(SWEAT_RATE_MIN_L_H, Math.min(rate, SWEAT_RATE_MAX_L_H)), 2);
    }

    static long hydrationTargetPerHour(double sweatLH) {
        return Py.roundInt(Math.min(sweatLH * 1000.0 * FLUID_REPLACE_FRACTION, MAX_FLUID_ML_H));
    }

    static long sodiumTargetPerHour(double sweatLH) {
        return Py.roundInt(Math.max(SODIUM_MIN_MG_H,
                Math.min(sweatLH * SODIUM_MG_PER_L_SWEAT, SODIUM_MAX_MG_H)));
    }

    static long gelCount(double carbGH, double gelG) {
        if (carbGH <= 0 || gelG <= 0) return 0;
        return (long) Math.ceil(carbGH / gelG);
    }

    static long dailyCarbTarget(double weightKg, double weeklyHours) {
        double dailyHours = Math.max(weeklyHours, 0.0) / 7.0;
        for (double[] b : DAILY_CARB_G_KG) {
            if (dailyHours < b[0]) return Py.roundInt(weightKg * b[1]);
        }
        return Py.roundInt(weightKg * DAILY_CARB_G_KG[DAILY_CARB_G_KG.length - 1][1]);
    }

    static long recoveryTarget(double weightKg) {
        return Py.roundInt(weightKg * RECOVERY_G_KG);
    }

    // ── Endpoints ────────────────────────────────────────────────────────────

    /** compute_workout_fueling: full fueling for one planned workout. */
    public static Map<String, Object> computeWorkoutFueling(
            Integer workoutDurationS, String workoutIntensity,
            Double gelCarbG, Double bodyWeightKg, Double sweatRateLH) {
        double durationS = workoutDurationS == null ? 0.0 : (double) workoutDurationS;
        String intensity = (workoutIntensity == null || workoutIntensity.isEmpty())
                ? "endurance" : workoutIntensity;
        double hours = durationS / 3600.0;

        Map<String, Object> out = new LinkedHashMap<>();
        if (durationS < MIN_FUELED_DURATION_S) {
            out.put("needed", false);
            out.put("duration_s", (long) durationS);
            out.put("note", "Under 45 min — no in-session fueling needed. Start well-hydrated.");
            return out;
        }

        long carbGH = carbTargetPerHour(durationS, intensity);
        double gelG = (gelCarbG == null || gelCarbG == 0.0) ? DEFAULT_GEL_CARB_G : gelCarbG;
        long gelsH = gelCount(carbGH, gelG);

        boolean hasWeight = bodyWeightKg != null && bodyWeightKg != 0.0;
        // Mirror Python truthiness EXACTLY. `if sweat is None and weight` only
        // estimates when sweat is literally absent — a stored 0.0 is present but
        // falsy, so it is NOT estimated. `if sweat` (and the field's `if sweat
        // else None`) then treat 0.0 as falsy → no hydration/sodium, null sweat.
        Double sweat = sweatRateLH;                       // keep 0.0 as-is (not null)
        if (sweat == null && hasWeight) sweat = estimateSweatRate(bodyWeightKg, intensity);
        boolean sweatTruthy = sweat != null && sweat != 0.0;
        Long fluidMlH = null, sodiumMgH = null;
        if (sweatTruthy) {
            fluidMlH = hydrationTargetPerHour(sweat);
            sodiumMgH = sodiumTargetPerHour(sweat);
        }

        out.put("needed", carbGH > 0 || fluidMlH != null);
        out.put("duration_s", (long) durationS);
        out.put("intensity", intensity);
        out.put("carb_g_h", carbGH);
        out.put("carb_total_g", Py.roundInt(carbGH * hours));
        out.put("mtc_required", needsMtc(carbGH));
        out.put("gel_carb_g", gelG);
        out.put("gels_per_hour", gelsH);
        out.put("gels_total", carbGH != 0 ? (long) Math.ceil(carbGH * hours / gelG) : 0L);
        out.put("high_carb_gels_total",
                carbGH != 0 ? (long) Math.ceil(carbGH * hours / HIGH_CARB_GEL_G) : 0L);
        // `round(x, 2) if sweat else None` / `round(rate*h) if rate else None`:
        // 0 is falsy in Python, so a zero-valued rate emits null, not 0.
        out.put("sweat_rate_l_h", sweatTruthy ? Py.round(sweat, 2) : null);
        out.put("fluid_ml_h", fluidMlH);
        out.put("fluid_total_ml", (fluidMlH != null && fluidMlH != 0L) ? Py.roundInt(fluidMlH * hours) : null);
        out.put("sodium_mg_h", sodiumMgH);
        out.put("sodium_total_mg", (sodiumMgH != null && sodiumMgH != 0L) ? Py.roundInt(sodiumMgH * hours) : null);
        if (hasWeight) {
            out.put("recovery_carb_g", recoveryTarget(bodyWeightKg));
        } else {
            out.put("note", "Set body weight in Thresholds for hydration & sodium targets.");
        }
        return out;
    }

    /** /daily: daily carb target from body weight + training volume. */
    public static Map<String, Object> computeDaily(Double bodyWeightKg, Double weeklyHoursTarget) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (bodyWeightKg == null || bodyWeightKg == 0.0) {
            out.put("body_weight_kg", null);
            out.put("daily_carb_g", null);
            out.put("note", "Set body weight in Thresholds for daily carb targets.");
            return out;
        }
        double weekly = (weeklyHoursTarget == null || weeklyHoursTarget == 0.0)
                ? 0.0 : weeklyHoursTarget;
        Map<String, Object> preRace = new LinkedHashMap<>();
        preRace.put("meal_3h_g", Py.roundInt(bodyWeightKg * PRE_RACE_MEAL_G_KG));
        preRace.put("snack_1h_g", Py.roundInt(bodyWeightKg * PRE_RACE_SNACK_G_KG));
        out.put("body_weight_kg", bodyWeightKg);
        out.put("weekly_hours", weekly);
        out.put("daily_carb_g", dailyCarbTarget(bodyWeightKg, weekly));
        out.put("pre_race", preRace);
        out.put("recovery_carb_g", recoveryTarget(bodyWeightKg));
        return out;
    }
}
