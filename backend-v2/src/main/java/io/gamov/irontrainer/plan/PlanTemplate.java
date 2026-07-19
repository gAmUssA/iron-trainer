package io.gamov.irontrainer.plan;

import io.gamov.irontrainer.util.Py;
import io.gamov.irontrainer.zones.HrZones;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic 70.3 plan structure — faithful port of app/planning/template.py.
 * Builds the season skeleton (phases, weekly volume ramp, recovery cadence) and
 * expands any week into concrete structured workouts with power/pace/HR targets
 * from the athlete's thresholds. Used as the offline fallback AND the structural
 * prior handed to the LLM. Pure; no I/O. */
public final class PlanTemplate {

    private PlanTemplate() {}

    // Fraction of weekly volume per sport.
    static final double SWIM_SPLIT = 0.20, BIKE_SPLIT = 0.50, RUN_SPLIT = 0.30;

    // Intensity multipliers vs threshold, (low, high).
    private static final Map<String, double[]> BIKE_PCT_FTP = Map.of(
            "recovery", new double[] {0.50, 0.60}, "endurance", new double[] {0.65, 0.75},
            "tempo", new double[] {0.76, 0.87}, "threshold", new double[] {0.95, 1.05},
            "vo2", new double[] {1.10, 1.20});
    private static final Map<String, double[]> RUN_PACE_FACTOR = Map.of(
            "recovery", new double[] {1.25, 1.35}, "endurance", new double[] {1.15, 1.25},
            "tempo", new double[] {1.06, 1.10}, "threshold", new double[] {0.99, 1.02},
            "vo2", new double[] {0.92, 0.96});
    private static final Map<String, double[]> SWIM_PACE_FACTOR = Map.of(
            "recovery", new double[] {1.12, 1.20}, "endurance", new double[] {1.06, 1.12},
            "tempo", new double[] {1.02, 1.05}, "threshold", new double[] {0.98, 1.02},
            "vo2", new double[] {0.94, 0.97});

    static LocalDate mondayOf(LocalDate d) {
        return d.minusDays(d.getDayOfWeek().getValue() - 1L);
    }

    /** build_season: the week-by-week skeleton from `start` to race week. */
    public static Map<String, Object> buildSeason(LocalDate start, LocalDate raceDate, double weeklyHours) {
        weeklyHours = Math.max(weeklyHours == 0.0 ? 6.0 : weeklyHours, 4.0);   // max(weekly or 6.0, 4.0)
        LocalDate firstMonday = mondayOf(start);
        LocalDate raceMonday = mondayOf(raceDate);
        long daysBetween = raceMonday.toEpochDay() - firstMonday.toEpochDay();
        int nWeeks = Math.max((int) (daysBetween / 7) + 1, 4);

        List<Map<String, Object>> weeks = new ArrayList<>();
        int buildIndex = 0;
        for (int i = 0; i < nWeeks; i++) {
            LocalDate ws = firstMonday.plusWeeks(i);
            int fromEnd = nWeeks - 1 - i;
            boolean isTaper = fromEnd < 2;
            boolean isRecovery = (i % 4 == 3) && !isTaper;

            String phase;
            if (isTaper) {
                phase = "taper";
            } else if (fromEnd < 4) {
                phase = "peak";
            } else if (i < nWeeks * 0.35) {
                phase = "base";
            } else {
                phase = "build";
            }

            double hours;
            if (isTaper || isRecovery) {
                hours = weeklyHours;
            } else {
                hours = weeklyHours * Math.pow(1.06, buildIndex);
                buildIndex++;
            }

            Map<String, Object> w = new LinkedHashMap<>();
            w.put("week_index", i + 1);
            w.put("week_start", ws.toString());
            w.put("phase", phase);
            w.put("is_recovery", isRecovery);
            w.put("focus", focus(phase, isRecovery));
            w.put("target_hours", Py.round(hours, 1));
            w.put("target_tss", Py.round(hours * 60, 0));   // round(_, 0) → float, like Python
            weeks.add(w);
        }

        Map<String, Object> season = new LinkedHashMap<>();
        season.put("race_name", "IRONMAN 70.3");
        season.put("race_date", raceDate.toString());
        season.put("start_date", firstMonday.toString());
        season.put("summary", nWeeks + "-week 70.3 build: base → build → peak → 2-week taper, "
                + "with recovery weeks every 4th week.");
        season.put("weeks", weeks);
        return season;
    }

    private static String focus(String phase, boolean recovery) {
        if (recovery) {
            return "Recovery week — reduced volume, keep frequency, easy intensity.";
        }
        return switch (phase) {
            case "base" -> "Aerobic base: long easy sessions, swim technique, build durability.";
            case "build" -> "Add threshold work on bike/run; longer rides; open-water if possible.";
            case "peak" -> "Race-specific: 70.3 effort bricks, biggest long ride, race nutrition.";
            case "taper" -> "Sharpen and freshen: cut volume, keep short race-pace efforts.";
            default -> "";
        };
    }

    // ── Week expansion ────────────────────────────────────────────────────────

    /** A (sport, dow, hours, intensity, title) template slot. */
    private record Slot(String sport, int dow, double hours, String intensity, String title) {}

    /** expand_week: one skeleton week → structured daily workouts. */
    public static List<Map<String, Object>> expandWeek(Map<String, Object> week, Map<String, Object> profile) {
        LocalDate ws = LocalDate.parse((String) week.get("week_start"));
        // Python `week.get("target_hours") or 6.0`: a null OR 0.0/falsy value → 6.0.
        Object th = week.get("target_hours");
        double hours = (th == null || ((Number) th).doubleValue() == 0.0) ? 6.0 : ((Number) th).doubleValue();
        boolean recovery = Boolean.TRUE.equals(week.get("is_recovery"));
        // Python `week.get("phase", "build")`: default ONLY on a MISSING key; a
        // present null stays null (→ "hard" falls to tempo, not threshold).
        String phase = week.containsKey("phase") ? (String) week.get("phase") : "build";

        double swimH = hours * SWIM_SPLIT, bikeH = hours * BIKE_SPLIT, runH = hours * RUN_SPLIT;
        String hard = recovery ? "endurance" : (("build".equals(phase) || "peak".equals(phase)) ? "threshold" : "tempo");

        List<Slot> plan = List.of(
                new Slot("Swim", 1, swimH * 0.45, "endurance", "Technique + aerobic swim"),
                new Slot("Bike", 1, bikeH * 0.30, hard, "Bike intervals"),
                new Slot("Run", 2, runH * 0.35, hard, "Run quality session"),
                new Slot("Swim", 3, swimH * 0.55, recovery ? "endurance" : "threshold", "Swim CSS set"),
                new Slot("Bike", 5, bikeH * 0.70, "endurance", "Long endurance ride"),
                new Slot("Run", 6, runH * 0.65, "endurance", "Long run"));

        List<Map<String, Object>> workouts = new ArrayList<>();
        for (Slot s : plan) {
            int durS = (int) (Math.max(s.hours(), 0.3) * 3600);   // int() truncates
            LocalDate wd = ws.plusDays(s.dow());
            StepsResult sr = buildSteps(s.sport(), s.intensity(), durS, profile);
            Map<String, Object> wo = new LinkedHashMap<>();
            wo.put("date", wd.toString());
            wo.put("sport", s.sport());
            wo.put("title", s.title());
            wo.put("description", describe(s.title(), s.intensity(), profile));
            wo.put("intensity", s.intensity());
            wo.put("duration_s", durS);
            wo.put("distance_m", sr.distance());
            wo.put("planned_tss", Py.round(durS / 3600.0 * Math.pow(ifFor(s.intensity()), 2) * 100, 1));
            wo.put("steps", sr.steps());
            workouts.add(wo);
        }
        return workouts;
    }

    private static String describe(String title, String intensity, Map<String, Object> profile) {
        String zone = HrZones.zoneLabel(intensity);
        int[] hr = HrZones.hrRangeForIntensity(intensity, intFromProfile(profile, "threshold_hr"),
                intFromProfile(profile, "max_hr"));
        if (zone != null && hr != null) {
            return String.format("%s — %s (%s · HR %d–%d bpm).", title, intensity, zone, hr[0], hr[1]);
        }
        if (zone != null) {
            return String.format("%s — %s (%s).", title, intensity, zone);
        }
        return String.format("%s — %s.", title, intensity);
    }

    private static double ifFor(String intensity) {
        return switch (intensity) {
            case "recovery" -> 0.55;
            case "endurance" -> 0.70;
            case "tempo" -> 0.85;
            case "threshold" -> 0.98;
            case "vo2" -> 1.10;
            default -> 0.70;
        };
    }

    private record StepsResult(List<Map<String, Object>> steps, Double distance) {}

    private static StepsResult buildSteps(String sport, String intensity, int durS, Map<String, Object> profile) {
        int warm = (int) (durS * 0.15);
        int cool = (int) (durS * 0.10);
        int main = durS - warm - cool;

        if ("Bike".equals(sport)) {
            Double ftp = dblFromProfile(profile, "ftp");
            Map<String, Object> target, easy;
            if (ftp != null && ftp != 0.0) {
                target = rangeTarget("power", BIKE_PCT_FTP.get(intensity), ftp, "W");
                easy = rangeTarget("power", BIKE_PCT_FTP.get("endurance"), ftp, "W");
            } else {
                target = hrTarget(intensity, profile);
                easy = hrTarget("endurance", profile);
            }
            return new StepsResult(steps(warm, main, cool, easy, target), null);
        }

        if ("Run".equals(sport)) {
            Double thr = dblFromProfile(profile, "threshold_pace_run");
            Map<String, Object> target, easy;
            if (thr != null && thr != 0.0) {
                target = rangeTarget("pace", RUN_PACE_FACTOR.get(intensity), thr, "sec_per_km");
                easy = rangeTarget("pace", RUN_PACE_FACTOR.get("endurance"), thr, "sec_per_km");
            } else {
                target = hrTarget(intensity, profile);
                easy = hrTarget("endurance", profile);
            }
            return new StepsResult(steps(warm, main, cool, easy, target), estDistanceRun(durS, thr, intensity));
        }

        // Swim
        Double css = dblFromProfile(profile, "css_swim");
        Map<String, Object> target = rangeTarget("pace", SWIM_PACE_FACTOR.get(intensity), css, "sec_per_100m");
        Map<String, Object> easy = rangeTarget("pace", SWIM_PACE_FACTOR.get("endurance"), css, "sec_per_100m");
        return new StepsResult(steps(warm, main, cool, easy, target), estDistanceSwim(durS, css, intensity));
    }

    private static List<Map<String, Object>> steps(int warm, int main, int cool,
                                                   Map<String, Object> easy, Map<String, Object> target) {
        return List.of(
                step("warmup", warm, easy),
                step("steady", main, target),
                step("cooldown", cool, easy));
    }

    private static Map<String, Object> step(String type, int durationS, Map<String, Object> target) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", type);
        s.put("duration_s", durationS);
        s.put("target", target);
        return s;
    }

    private static Map<String, Object> hrTarget(String intensity, Map<String, Object> profile) {
        int[] hr = HrZones.hrRangeForIntensity(intensity, intFromProfile(profile, "threshold_hr"),
                intFromProfile(profile, "max_hr"));
        Map<String, Object> t = new LinkedHashMap<>();
        if (hr == null) {
            t.put("type", "open");
            t.put("unit", "");
            t.put("low", null);
            t.put("high", null);
            return t;
        }
        t.put("type", "hr");
        t.put("unit", "bpm");
        t.put("low", hr[0]);
        t.put("high", hr[1]);
        return t;
    }

    private static Map<String, Object> rangeTarget(String kind, double[] factors, Double base, String unit) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", kind);
        t.put("unit", unit);
        if (base == null) {
            t.put("low", null);
            t.put("high", null);
            return t;
        }
        if ("W".equals(unit)) {
            t.put("low", Py.roundInt(base * factors[0]));
            t.put("high", Py.roundInt(base * factors[1]));
            return t;
        }
        long lo = Py.roundInt(base * factors[0]);
        long hi = Py.roundInt(base * factors[1]);
        t.put("low", Math.min(lo, hi));
        t.put("high", Math.max(lo, hi));
        return t;
    }

    private static Double estDistanceRun(int durS, Double thrPace, String intensity) {
        if (thrPace == null || thrPace == 0.0) {
            return null;
        }
        double[] f = RUN_PACE_FACTOR.get(intensity);
        double mid = (f[0] + f[1]) / 2;
        double pace = thrPace * mid;
        return (double) Py.roundInt(durS / pace * 1000);
    }

    private static Double estDistanceSwim(int durS, Double css, String intensity) {
        if (css == null || css == 0.0) {
            return null;
        }
        double[] f = SWIM_PACE_FACTOR.get(intensity);
        double mid = (f[0] + f[1]) / 2;
        double pace = css * mid;
        return (double) Py.roundInt(durS / pace * 100);
    }

    private static Double dblFromProfile(Map<String, Object> profile, String key) {
        Object v = profile.get(key);
        return v == null ? null : ((Number) v).doubleValue();
    }

    private static Integer intFromProfile(Map<String, Object> profile, String key) {
        Object v = profile.get(key);
        return v == null ? null : ((Number) v).intValue();
    }
}
