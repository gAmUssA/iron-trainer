package io.gamov.irontrainer.tests;

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
}
