package io.gamov.irontrainer.plan;

import io.gamov.irontrainer.util.Py;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic safety validator — faithful port of app/planning/validator.py.
 * Clamps unsafe ramps, enforces recovery cadence + a 2-week taper, caps absolute
 * volume, and returns the corrected season plus human-readable adjustments. The
 * adjustment strings are byte-parity (they surface as the response `adjustments`).
 * Pure; no I/O. */
public final class PlanValidator {

    private PlanValidator() {}

    private static final double MAX_WEEKLY_RAMP = 1.10;
    private static final int RECOVERY_EVERY = 4;
    private static final double RECOVERY_FACTOR = 0.65;
    private static final double MIN_WEEK_HOURS = 2.0;
    private static final double ABS_MAX_WEEK_HOURS = 16.0;
    private static final int TAPER_WEEKS = 2;

    private static final Map<String, Double> SESSION_CAPS_H = Map.of(
            "Bike", 4.0, "Run", 2.5, "Swim", 1.5, "Brick", 4.5);

    public record Result(Map<String, Object> season, List<String> notes) {}

    @SuppressWarnings("unchecked")
    public static Result validateSeason(Map<String, Object> season) {
        List<Map<String, Object>> src = (List<Map<String, Object>>) season.getOrDefault("weeks", List.of());
        List<Map<String, Object>> weeks = new ArrayList<>();
        for (Map<String, Object> w : src) {
            weeks.add(new LinkedHashMap<>(w));   // [dict(w) for w in weeks]
        }
        List<String> notes = new ArrayList<>();
        int n = weeks.size();
        if (n == 0) {
            return new Result(season, new ArrayList<>(List.of("No weeks in plan.")));
        }

        // 1) Taper: force the last TAPER_WEEKS into taper.
        for (Map<String, Object> w : weeks) {
            w.put("phase", w.getOrDefault("phase", "build"));
        }
        for (int offset = 1; offset <= TAPER_WEEKS; offset++) {
            int idx = n - offset;
            if (idx >= 0) {
                weeks.get(idx).put("phase", "taper");
            }
        }

        // 2) Recovery cadence before the taper.
        int buildStreak = 0;
        int lastTaperStart = n - TAPER_WEEKS;
        for (int i = 0; i < lastTaperStart; i++) {
            if (truthy(weeks.get(i).get("is_recovery"))) {
                buildStreak = 0;
                continue;
            }
            buildStreak++;
            if (buildStreak >= RECOVERY_EVERY) {
                weeks.get(i).put("is_recovery", true);
                notes.add("Week " + (i + 1) + ": inserted recovery week (≥" + RECOVERY_EVERY
                        + " hard weeks in a row).");
                buildStreak = 0;
            }
        }

        // 3) Ramp cap + recovery/taper scaling + absolute bounds.
        Double prevBuildHours = null;
        for (int i = 0; i < n; i++) {
            Map<String, Object> w = weeks.get(i);
            double hours = w.get("target_hours") == null ? 0.0 : ((Number) w.get("target_hours")).doubleValue();
            boolean isTaper = "taper".equals(w.get("phase"));
            boolean isRecovery = truthy(w.get("is_recovery"));

            if (isTaper) {
                double base = prevBuildHours != null ? prevBuildHours : hours;
                int taperPos = i - lastTaperStart;
                double factor = taperPos == 0 ? 0.6 : 0.4;
                double target = base * factor;
                if (Math.abs(hours - target) > 0.1) {
                    notes.add("Week " + (i + 1) + ": taper set to " + Py.f1(target) + "h (was "
                            + Py.f1(hours) + "h).");
                }
                hours = target;
            } else if (isRecovery) {
                double base = prevBuildHours != null ? prevBuildHours : hours;
                double target = base * RECOVERY_FACTOR;
                if (hours > target + 0.1) {
                    notes.add("Week " + (i + 1) + ": recovery reduced to " + Py.f1(target) + "h (was "
                            + Py.f1(hours) + "h).");
                    hours = target;
                }
            } else {
                if (prevBuildHours != null && hours > prevBuildHours * MAX_WEEKLY_RAMP) {
                    double capped = prevBuildHours * MAX_WEEKLY_RAMP;
                    notes.add("Week " + (i + 1) + ": ramp capped to " + Py.f1(capped) + "h (was "
                            + Py.f1(hours) + "h, +10% max).");
                    hours = capped;
                }
            }

            if (hours > ABS_MAX_WEEK_HOURS) {
                notes.add("Week " + (i + 1) + ": clamped to " + Py.f1(ABS_MAX_WEEK_HOURS) + "h ceiling.");
                hours = ABS_MAX_WEEK_HOURS;
            }
            if (hours < MIN_WEEK_HOURS) {
                hours = MIN_WEEK_HOURS;
            }

            double rounded = Py.round(hours, 1);
            w.put("target_hours", rounded);
            if (!isTaper && !isRecovery) {
                prevBuildHours = rounded;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>(season);
        out.put("weeks", weeks);
        return new Result(out, notes);
    }

    /** validate_week_workouts: cap individual session durations. (The caller
     * discards the notes; only the duration caps mutate the workouts.) */
    public static List<Map<String, Object>> capWeekWorkouts(List<Map<String, Object>> workouts) {
        List<Map<String, Object>> fixed = new ArrayList<>();
        for (Map<String, Object> w : workouts) {
            Map<String, Object> c = new LinkedHashMap<>(w);
            double capH = SESSION_CAPS_H.getOrDefault(String.valueOf(c.getOrDefault("sport", "")), 4.0);
            double dur = c.get("duration_s") == null ? 0.0 : ((Number) c.get("duration_s")).doubleValue();
            if (dur > capH * 3600) {
                c.put("duration_s", (int) (capH * 3600));
            }
            fixed.add(c);
        }
        return fixed;
    }

    // ABS_MAX_WEEK_HOURS clamp note formats "16.0" via f-string on a float 16.0.
    // MIN clamp note: Python's clamp-up has no note (silent), matching here.

    private static boolean truthy(Object v) {
        return v instanceof Boolean b && b;
    }
}
