package io.gamov.irontrainer.nutrition;

import io.gamov.irontrainer.util.Py;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    static final double PLAIN_WATER_SAFE_ML_H = 750.0;

    // Absolute per-item ceilings for phases with no duration (transitions ~a
    // minute; meals a single sitting) where hourly rates don't apply.
    static final double TRANSITION_MAX_CARB_G = 80.0;
    static final double TRANSITION_MAX_FLUID_ML = 500.0;
    static final double MEAL_MAX_CARB_G = 300.0;
    static final double MEAL_MAX_FLUID_ML = 1000.0;
    static final Set<String> TRANSITION_PHASES = Set.of("t1", "t2");

    // Fallback projected leg durations (seconds) when readiness has no projection.
    static final Map<String, Map<String, Integer>> DEFAULT_LEGS_S = Map.of(
        "70.3", Map.of("swim", 45 * 60, "bike", 3 * 3600, "run", 2 * 3600),
        "140.6", Map.of("swim", 80 * 60, "bike", 6 * 3600 + 30 * 60, "run", 4 * 3600 + 30 * 60));

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

    /** fueling_note: one-line fueling summary appended to a workout description /
     * export. Port of nutrition.fueling_note (used by plan generation). */
    public static String fuelingNote(Map<String, Object> fueling) {
        if (!Boolean.TRUE.equals(fueling.get("needed"))) {
            return "";
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        long carbGH = ((Number) fueling.get("carb_g_h")).longValue();
        parts.add("Fuel: " + Py.f0(carbGH) + " g carbs/h (~" + fueling.get("gels_per_hour") + " gels/h)");
        if (Boolean.TRUE.equals(fueling.get("mtc_required"))) {
            parts.add("use glucose:fructose blend");
        }
        Object fluid = fueling.get("fluid_ml_h");
        if (fluid != null && ((Number) fluid).doubleValue() != 0.0) {
            parts.add(Py.f0(((Number) fluid).doubleValue()) + " mL fluid/h");
        }
        Object sodium = fueling.get("sodium_mg_h");
        if (sodium != null && ((Number) sodium).doubleValue() != 0.0) {
            parts.add(Py.f0(((Number) sodium).doubleValue()) + " mg sodium/h");
        }
        return String.join(" · ", parts);
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

    // ── Race-day plan (deterministic fallback / LLM prior) ───────────────────

    /** GET /api/nutrition/race-day (deterministic): the full race-day fueling
     * timeline. `readiness` is the race_readiness projection (RaceReadiness) —
     * its per-leg "seconds" override the default leg durations. Faithful port of
     * compute_race_day_plan; runs through validateFueling like FastAPI. */
    public static Map<String, Object> computeRaceDayPlan(
            Double bodyWeightKg, Double gelCarbG, Double sweatRateLH,
            Map<String, Object> race, Map<String, Object> readiness) {
        boolean hasWeight = bodyWeightKg != null && bodyWeightKg != 0.0;
        double gelG = (gelCarbG == null || gelCarbG == 0.0) ? DEFAULT_GEL_CARB_G : gelCarbG;
        Object distO = race == null ? null : race.get("distance");
        String distance = (distO == null || String.valueOf(distO).isEmpty())
                ? "70.3" : String.valueOf(distO);
        Map<String, Integer> legs = new LinkedHashMap<>(
                DEFAULT_LEGS_S.getOrDefault(distance, DEFAULT_LEGS_S.get("70.3")));
        for (String name : List.of("swim", "bike", "run")) {
            Integer proj = projSeconds(readiness, name);
            if (proj != null && proj != 0) legs.put(name, proj);   // `if proj:` truthy
        }

        // sweat: measured override, else estimate (only when weight is truthy).
        Double sweat = sweatRateLH;
        if (sweat == null && hasWeight) sweat = estimateSweatRate(bodyWeightKg, "tempo");
        boolean sweatTruthy = sweat != null && sweat != 0.0;

        List<Map<String, Object>> items = new ArrayList<>();
        List<String> adjustments = new ArrayList<>();

        if (hasWeight) {
            long meal3h = Py.roundInt(bodyWeightKg * PRE_RACE_MEAL_G_KG);
            long snack1h = Py.roundInt(bodyWeightKg * PRE_RACE_SNACK_G_KG);
            items.add(item("pre_race", -210L, "Pre-race meal", meal3h, 500L, null,
                    "Familiar, low-fibre carbs: oatmeal, toast, banana. 3-4 h before the start."));
            items.add(item("pre_race", -60L, "Pre-race snack", snack1h, 300L, 300L,
                    "Energy bar or sports drink; sip electrolytes until the start."));
        } else {
            adjustments.add("Body weight not set — pre-race and recovery amounts omitted.");
        }

        items.add(item("swim", 0L, "Swim", 0L, 0L, 0L,
                "No fueling. Optionally one gel with a sip of water 15 min before the gun."));

        long swimMin = Py.roundInt(legs.get("swim") / 60.0);
        double bikeH = legs.get("bike") / 3600.0;
        long bikeCarbH = carbTargetPerHour(legs.get("bike"), "tempo");
        Long bikeFluidH = sweatTruthy ? hydrationTargetPerHour(sweat) : null;
        Long bikeSodiumH = sweatTruthy ? sodiumTargetPerHour(sweat) : null;
        items.add(item("t1", swimMin, "T1", Py.roundInt(gelG), 200L, null,
                "One gel + water while transitioning."));
        long gelInterval = bikeCarbH != 0 ? Math.max(Py.roundInt(60.0 * gelG / bikeCarbH), 15L) : 60L;
        String bikeNotes = Py.f0(bikeCarbH) + " g/h — one " + Py.f0(gelG) + " g gel every "
                + gelInterval + " min"
                + (needsMtc(bikeCarbH) ? " (glucose:fructose blend above 60 g/h)" : "")
                + ". The bike is where most of the eating happens.";
        Map<String, Object> bike = item("bike", swimMin + 5, "Bike",
                Py.roundInt(bikeCarbH * bikeH),
                bikeFluidH != null ? Py.roundInt(bikeFluidH * bikeH) : null,
                bikeSodiumH != null ? Py.roundInt(bikeSodiumH * bikeH) : null,
                bikeNotes);
        bike.put("phase_duration_s", (long) legs.get("bike"));
        items.add(bike);

        double runH = legs.get("run") / 3600.0;
        double runCarbH = Math.min((double) carbTargetPerHour(legs.get("run"), "tempo"), 60.0);
        Long runFluidH = sweatTruthy ? hydrationTargetPerHour(sweat) : null;
        Long runSodiumH = sweatTruthy ? sodiumTargetPerHour(sweat) : null;
        long bikeDoneMin = Py.roundInt((legs.get("swim") + 5 * 60 + legs.get("bike")) / 60.0);
        items.add(item("t2", bikeDoneMin, "T2", Py.roundInt(gelG), 200L, null,
                "One gel + water heading out on the run."));
        Map<String, Object> run = item("run", bikeDoneMin + 3, "Run",
                Py.roundInt(runCarbH * runH),
                runFluidH != null ? Py.roundInt(runFluidH * runH) : null,
                runSodiumH != null ? Py.roundInt(runSodiumH * runH) : null,
                Py.f0(runCarbH) + " g/h — gel or chews every 20-25 min, fluid at every aid station.");
        run.put("phase_duration_s", (long) legs.get("run"));
        items.add(run);

        if (hasWeight) {
            items.add(item("post_race", bikeDoneMin + 3 + Py.roundInt(runH * 60.0) + 15,
                    "Recovery", recoveryTarget(bodyWeightKg), 500L, 500L,
                    "1.2 g/kg carbs + ~25 g protein within 30 min of finishing."));
        }

        long totalCarbs = 0;
        for (Map<String, Object> i : items) {
            Object ph = i.get("phase");
            if ("t1".equals(ph) || "bike".equals(ph) || "t2".equals(ph) || "run".equals(ph)) {
                totalCarbs += (long) num(i.get("carbs_g"));
            }
        }
        String summary = "~" + totalCarbs + " g carbs across the race "
                + "(bike " + Py.f0(bikeCarbH) + " g/h, run " + Py.f0(runCarbH) + " g/h)"
                + (bikeFluidH != null ? ", fluid ~" + Py.f0(bikeFluidH) + " mL/h" : "")
                + ". Practice this in training — nothing new on race day.";

        Map<String, Object> raceOut = new LinkedHashMap<>();
        raceOut.put("name", race == null ? null : race.get("name"));
        raceOut.put("date", race == null ? null : race.get("date"));
        raceOut.put("distance", distance);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("race", raceOut);
        plan.put("summary", summary);
        plan.put("gel_carb_g", gelG);
        plan.put("items", items);
        plan.put("llm_used", false);

        List<String> notes = validateFueling(plan);   // clamps items in place, returns adjustments
        List<String> adj = new ArrayList<>(adjustments);
        adj.addAll(notes);
        plan.put("adjustments", adj);
        return plan;
    }

    /** Clamp unsafe fueling values in-place; return the adjustment notes. Rates
     * are checked per PHASE (summed across its items); duration-less items get
     * absolute per-item ceilings. Faithful port of validate_fueling. */
    static List<String> validateFueling(Map<String, Object> plan) {
        List<String> notes = new ArrayList<>();
        List<Map<String, Object>> items = new ArrayList<>();
        Object itemsO = plan.get("items");
        if (itemsO instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mm = (Map<String, Object>) m;
                    items.add(new LinkedHashMap<>(mm));
                }
            }
        }

        // A phase's duration is set by any member carrying (phase_)duration_s.
        Map<String, Double> phaseDurH = new LinkedHashMap<>();
        for (Map<String, Object> i : items) {
            Double d = phaseHours(i);
            Object ph = i.get("phase");
            if (d != null && ph != null && !phaseDurH.containsKey(ph)) {
                phaseDurH.put(String.valueOf(ph), d);
            }
        }

        // 1) Phases with a duration: rate-check totals, scale members to fit.
        for (Map.Entry<String, Double> e : phaseDurH.entrySet()) {
            String phase = e.getKey();
            double durH = e.getValue();
            List<Map<String, Object>> members = new ArrayList<>();
            for (Map<String, Object> i : items) {
                if (phase.equals(i.get("phase"))) members.add(i);
            }

            double carbs = 0;
            for (Map<String, Object> i : members) carbs += num(i.get("carbs_g"));
            if (carbs > MAX_CARB_G_H * durH) {
                double scale = (MAX_CARB_G_H * durH) / carbs;
                for (Map<String, Object> i : members) {
                    double c = num(i.get("carbs_g"));
                    if (c != 0) i.put("carbs_g", Py.roundInt(c * scale));
                }
                notes.add(phase + ": carbs capped at " + Py.f0(MAX_CARB_G_H) + " g/h.");
                carbs = MAX_CARB_G_H * durH;
            }
            if (carbs / durH > MTC_THRESHOLD_G_H) {
                for (Map<String, Object> i : members) {
                    double c = num(i.get("carbs_g"));
                    String nt = i.get("notes") == null ? "" : String.valueOf(i.get("notes"));
                    if (c != 0 && !nt.toLowerCase().contains("fructose")) {
                        i.put("notes", (nt + " Use a glucose:fructose blend above 60 g/h.").strip());
                    }
                }
            }

            double fluid = 0;
            for (Map<String, Object> i : members) fluid += num(i.get("fluid_ml"));
            if (fluid > MAX_FLUID_ML_H * durH) {
                double scale = (MAX_FLUID_ML_H * durH) / fluid;
                for (Map<String, Object> i : members) {
                    double f = num(i.get("fluid_ml"));
                    if (f != 0) i.put("fluid_ml", Py.roundInt(f * scale));
                }
                notes.add(phase + ": fluid capped at " + Py.f0(MAX_FLUID_ML_H)
                        + " mL/h (hyponatremia risk).");
                fluid = MAX_FLUID_ML_H * durH;
            }
            boolean anySodium = false;
            for (Map<String, Object> i : members) {
                if (num(i.get("sodium_mg")) != 0) anySodium = true;
            }
            if (fluid / durH > PLAIN_WATER_SAFE_ML_H && !anySodium) {
                notes.add(phase + ": >750 mL/h fluid without sodium — add electrolytes.");
            }
        }

        // 2) Duration-less items: absolute ceilings.
        for (Map<String, Object> i : items) {
            Object ph = i.get("phase");
            if ((ph != null && phaseDurH.containsKey(ph)) || "swim".equals(ph)) continue;
            // Python `i.get("label") or phase`: an empty-string label is falsy too.
            Object lab = i.get("label");
            String label = (lab != null && !String.valueOf(lab).isEmpty())
                    ? String.valueOf(lab) : String.valueOf(ph);
            boolean isTransition = ph != null && TRANSITION_PHASES.contains(ph);
            double carbCap = isTransition ? TRANSITION_MAX_CARB_G : MEAL_MAX_CARB_G;
            double fluidCap = isTransition ? TRANSITION_MAX_FLUID_ML : MEAL_MAX_FLUID_ML;
            if (num(i.get("carbs_g")) > carbCap) {
                i.put("carbs_g", Py.roundInt(carbCap));
                notes.add(label + ": carbs capped at " + Py.f0(carbCap) + " g.");
            }
            if (num(i.get("fluid_ml")) > fluidCap) {
                i.put("fluid_ml", Py.roundInt(fluidCap));
                notes.add(label + ": fluid capped at " + Py.f0(fluidCap) + " mL.");
            }
        }

        plan.put("items", items);
        return notes;
    }

    /** apply_llm_timeline: overlay the LLM-generated timeline onto the
     * deterministic base. The base stays the safety prior — each phase's duration
     * is carried over so validateFueling can still rate-check the LLM's amounts —
     * then re-clamp. Faithful port; does not mutate `base`. */
    public static Map<String, Object> applyLlmTimeline(Map<String, Object> base,
            String llmSummary, List<Map<String, Object>> llmItems) {
        // phase → phase_duration_s from the base (truthy only; last-wins, as the
        // Python dict comprehension).
        Map<String, Object> durations = new LinkedHashMap<>();
        if (base.get("items") instanceof List<?> baseItems) {
            for (Object o : baseItems) {
                if (o instanceof Map<?, ?> m) {
                    Object ph = m.get("phase");
                    Object dur = m.get("phase_duration_s");
                    if (ph != null && dur != null && num(dur) != 0) {
                        durations.put(String.valueOf(ph), dur);
                    }
                }
            }
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> raw : llmItems) {
            Map<String, Object> item = new LinkedHashMap<>(raw);
            Object dur = durations.get(String.valueOf(item.get("phase")));
            if (dur != null) item.put("phase_duration_s", dur);   // if dur: keep base duration
            items.add(item);
        }
        Map<String, Object> plan = new LinkedHashMap<>(base);
        plan.put("items", items.isEmpty() ? base.get("items") : items);
        if (llmSummary != null && !llmSummary.isEmpty()) plan.put("summary", llmSummary);
        plan.put("llm_used", true);
        List<String> notes = validateFueling(plan);   // clamps the LLM amounts in place
        plan.put("adjustments", notes);
        return plan;
    }

    /** Build a timeline item with the FastAPI key order. carbs/fluid/sodium are
     * Long (an integer field) or null (Python None). */
    private static Map<String, Object> item(String phase, long offsetMin, String label,
            Long carbsG, Long fluidMl, Long sodiumMg, String notes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", phase);
        m.put("offset_min", offsetMin);
        m.put("label", label);
        m.put("carbs_g", carbsG);
        m.put("fluid_ml", fluidMl);
        m.put("sodium_mg", sodiumMg);
        m.put("notes", notes);
        return m;
    }

    /** readiness["legs"][name]["seconds"], or null if absent — mirrors Python's
     * chained .get(...) with {} defaults. */
    private static Integer projSeconds(Map<String, Object> readiness, String name) {
        if (readiness == null) return null;
        if (!(readiness.get("legs") instanceof Map<?, ?> legs)) return null;
        if (!(legs.get(name) instanceof Map<?, ?> leg)) return null;
        return (leg.get("seconds") instanceof Number n) ? n.intValue() : null;
    }

    /** Python `x or 0` for a numeric field: null/non-number → 0.0. */
    private static double num(Object o) {
        return (o instanceof Number n) ? n.doubleValue() : 0.0;
    }

    /** Item duration in hours for rate checks; `duration_s or phase_duration_s`,
     * truthy (0/null → the next). */
    private static Double phaseHours(Map<String, Object> item) {
        double d = num(item.get("duration_s"));
        if (d == 0) d = num(item.get("phase_duration_s"));
        return d != 0 ? d / 3600.0 : null;
    }
}
