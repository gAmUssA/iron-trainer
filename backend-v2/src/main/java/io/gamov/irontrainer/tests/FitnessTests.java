package io.gamov.irontrainer.tests;

import io.gamov.irontrainer.util.Py;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fitness-test protocol catalog — faithful port of app/fitness_tests.py.
 * The catalog is a small fixed set (no DB table); it must serialize
 * byte-identically to FastAPI's catalog() (key order + exact copy). The compute
 * lambdas + to_workout live with the write vertical; reads only need this. */
public final class FitnessTests {

    private FitnessTests() {}

    public static final int RETEST_DAYS = 35;

    private static Map<String, Object> input(String field, String label, String unit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("label", label);
        m.put("unit", unit);
        return m;
    }

    private static Map<String, Object> proto(String slug, String name, String sport,
            List<String> measures, String description, List<Map<String, Object>> inputs,
            String prefillSport) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slug", slug);
        m.put("name", name);
        m.put("sport", sport);
        m.put("measures", measures);
        m.put("description", description);
        m.put("inputs", inputs);
        m.put("prefill_sport", prefillSport);
        return m;
    }

    /** Protocol defs, same order + content as the Python TESTS list. */
    static final List<Map<String, Object>> CATALOG = List.of(
        proto("bike-ftp-20", "Bike — 20-min FTP", "Bike",
            List.of("ftp"),
            "After a thorough warm-up, ride 20 minutes all-out at the "
                + "highest power you can sustain. FTP = 95% of your average power.",
            List.of(input("avg_power_w", "20-min avg power", "W")),
            "Bike"),
        proto("run-lthr-30", "Run — 30-min LTHR test", "Run",
            List.of("threshold_hr", "threshold_pace_run"),
            "Run 30 minutes as a steady, hard time trial. LTHR is your "
                + "average heart rate over the final 20 minutes; threshold pace "
                + "is your average pace for the effort.",
            List.of(
                input("distance_m", "Distance covered", "m"),
                input("time_s", "Duration", "s"),
                input("avg_hr_last20", "Avg HR (final 20 min)", "bpm")),
            "Run"),
        proto("swim-css-400-200", "Swim — CSS (400/200)", "Swim",
            List.of("css_swim"),
            "Swim a maximal 400 m and (after full recovery) a maximal "
                + "200 m. CSS pace per 100 m = (400 time − 200 time) / 2.",
            List.of(
                input("t400_s", "400 m time", "s"),
                input("t200_s", "200 m time", "s")),
            null)
    );

    /** Public protocol defs — the catalog is already lambda-free, so returned as-is. */
    public static List<Map<String, Object>> catalog() {
        return CATALOG;
    }

    /** Protocol by slug, or null when unknown. */
    public static Map<String, Object> get(String slug) {
        for (Map<String, Object> t : CATALOG) {
            if (t.get("slug").equals(slug)) return t;
        }
        return null;
    }

    // ── compute: raw inputs → thresholds (write side) ─────────────────────────

    /** A missing/non-numeric input — the resource maps it to 400, mirroring
     * Python's KeyError/TypeError from compute(). */
    public static final class BadInput extends RuntimeException {
        public BadInput(String message) { super(message); }
    }

    private static double num(Map<String, Object> inputs, String key) {
        Object v = inputs.get(key);
        if (v == null) throw new BadInput("'" + key + "'");        // ~ Python KeyError
        // Python's bool is an int subclass, so True/False are valid numeric input.
        if (v instanceof Boolean) return ((Boolean) v) ? 1.0 : 0.0;
        if (!(v instanceof Number)) throw new BadInput("invalid '" + key + "'");
        return ((Number) v).doubleValue();
    }

    /** _BY_SLUG[slug]["_fn"](inputs). Assumes a known slug (resource 404s first). */
    public static Map<String, Object> compute(String slug, Map<String, Object> inputs) {
        switch (slug) {
            case "bike-ftp-20":
                return map("ftp", Py.roundInt(num(inputs, "avg_power_w") * 0.95));
            case "run-lthr-30": {
                double distanceM = num(inputs, "distance_m");
                double timeS = num(inputs, "time_s");
                double avgHr = num(inputs, "avg_hr_last20");
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("threshold_hr", Py.roundInt(avgHr));
                if (distanceM > 0) {  // Python: if distance_m and distance_m > 0
                    out.put("threshold_pace_run", Py.roundInt(timeS / (distanceM / 1000.0)));
                }
                return out;
            }
            case "swim-css-400-200":
                return map("css_swim", Py.roundInt((num(inputs, "t400_s") - num(inputs, "t200_s")) / 2.0));
            default:
                throw new BadInput("unknown slug '" + slug + "'");
        }
    }

    private static Map<String, Object> map(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    // ── schedulable workout (write side) ──────────────────────────────────────

    private static Map<String, Object> openTarget() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "open");
        t.put("unit", "");
        t.put("low", null);
        t.put("high", null);
        return t;
    }

    private static Map<String, Object> easy(int durationS, String label) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", label);
        s.put("duration_s", durationS);
        s.put("target", openTarget());
        return s;
    }

    private static Map<String, Object> open(String notes, Integer durationS, Integer distanceM) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "interval");
        s.put("notes", notes);
        s.put("target", openTarget());
        if (durationS != null) s.put("duration_s", durationS);
        if (distanceM != null) s.put("distance_m", distanceM);
        return s;
    }

    /** Structured test workout (warmup → test → cooldown), intensity="test".
     * Same key order + step shapes as fitness_tests.to_workout. */
    public static Map<String, Object> toWorkout(String slug) {
        Map<String, Object> t = get(slug);
        String sport = (String) t.get("sport");
        List<Map<String, Object>> steps;
        int dur;
        if ("Bike".equals(sport)) {
            steps = List.of(easy(900, "warmup"),
                    open("20-min test: all-out, highest sustainable power", 1200, null),
                    easy(600, "cooldown"));
            dur = 900 + 1200 + 600;
        } else if ("Run".equals(sport)) {
            steps = List.of(easy(900, "warmup"),
                    open("30-min test: steady, hard time-trial effort", 1800, null),
                    easy(600, "cooldown"));
            dur = 900 + 1800 + 600;
        } else {  // Swim CSS
            steps = List.of(easy(300, "warmup"),
                    open("400 m time trial — maximal", null, 400),
                    easy(180, "recovery"),
                    open("200 m time trial — maximal", null, 200),
                    easy(200, "cooldown"));
            dur = 300 + 180 + 200;
        }
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("sport", sport);
        w.put("title", t.get("name") + " (test)");
        w.put("description", t.get("description"));
        w.put("intensity", "test");
        w.put("steps", steps);
        w.put("duration_s", dur);
        return w;
    }
}
