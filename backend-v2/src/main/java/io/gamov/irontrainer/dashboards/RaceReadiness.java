package io.gamov.irontrainer.dashboards;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.metrics.Metrics.Thresholds;
import io.gamov.irontrainer.util.Iso;
import io.gamov.irontrainer.util.Py;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Race-readiness projection — port of dashboards.race_readiness: estimate race
 * splits at current fitness (swim ~ CSS·1.06, bike ~ recent long-ride speed,
 * run ~ threshold pace·1.10) and compare cumulative times to the cut-offs. */
public final class RaceReadiness {

    private RaceReadiness() {}

    private static final Map<String, Map<String, Integer>> LEG_DISTANCES = Map.of(
            "70.3", Map.of("swim", 1900, "bike", 90_000, "run", 21_100),
            "140.6", Map.of("swim", 3860, "bike", 180_000, "run", 42_200));

    /** Average bike speed (m/s) on longer (>= 1h) rides in the last 84 days. */
    static Double recentBikeSpeed(List<Activity> activities) {
        LocalDate cutoff = LocalDate.now().minusDays(84);   // date.today() - 84d
        double sum = 0;
        int n = 0;
        for (Activity a : activities) {
            LocalDate d = Iso.parseDate(a.startDate);   // dashboards._day
            if (d == null || d.isBefore(cutoff) || !"Bike".equals(a.sport)) {
                continue;
            }
            int moving = a.movingTime != null ? a.movingTime : 0;
            if (moving >= 3600 && Py.truthy(a.avgSpeed)) {
                sum += a.avgSpeed;
                n++;
            }
        }
        return n > 0 ? sum / n : null;
    }

    /** _fmt_hms: int(seconds) then h:mm:ss (truncates — NOT rounded). */
    static String fmtHms(double seconds) {
        long s = (long) seconds;   // int() truncates toward zero
        long h = s / 3600;
        long rem = s % 3600;
        // Locale.ROOT: ASCII digits, no grouping — match Python's f-string.
        return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, rem / 60, rem % 60);
    }

    private static Map<String, Object> leg(double seconds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seconds", Py.roundInt(seconds));   // round(); display uses raw
        m.put("display", fmtHms(seconds));
        return m;
    }

    public static Map<String, Object> raceReadiness(List<Activity> activities, Thresholds th,
                                                    Double currentCtl, Map<String, Integer> cutoffs,
                                                    String distance) {
        Map<String, Object> legs = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        int t1 = 5 * 60;
        int t2 = 3 * 60;
        // Python: LEG_DISTANCES.get(str(distance), LEG_DISTANCES["70.3"]) — the
        // key is stringified first, so a null distance (no race selected) maps to
        // "null" → miss → the 70.3 default (Map.of is null-hostile, hence valueOf).
        Map<String, Integer> d = LEG_DISTANCES.getOrDefault(
                String.valueOf(distance), LEG_DISTANCES.get("70.3"));

        Double swimS = null;
        if (Py.truthy(th.cssSwim())) {
            double swimPace = th.cssSwim() * 1.06;             // sec/100m at race effort
            swimS = d.get("swim") / 100.0 * swimPace;
            legs.put("swim", leg(swimS));
        } else {
            missing.add("css_swim");
        }

        Double bikeS = null;
        Double bikeSpeed = recentBikeSpeed(activities);
        if (Py.truthy(bikeSpeed)) {
            bikeS = d.get("bike") / bikeSpeed;
            legs.put("bike", leg(bikeS));
        } else {
            missing.add("bike_speed_history");
        }

        Double runS = null;
        if (Py.truthy(th.thresholdPaceRun())) {
            double runPace = th.thresholdPaceRun() * 1.10;     // sec/km off the bike
            runS = d.get("run") / 1000.0 * runPace;
            legs.put("run", leg(runS));
        } else {
            missing.add("threshold_pace_run");
        }

        int transitionsS = t1 + t2;
        // total sums the ALREADY-ROUNDED leg seconds + transitions (if any legs).
        double totalS = 0;
        for (Object legO : legs.values()) {
            totalS += ((Number) ((Map<?, ?>) legO).get("seconds")).doubleValue();
        }
        if (!legs.isEmpty()) {
            totalS += transitionsS;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("legs", legs);
        Map<String, Object> trans = new LinkedHashMap<>();
        trans.put("seconds", transitionsS);
        trans.put("display", fmtHms(transitionsS));
        out.put("transitions", trans);
        out.put("total", legs.isEmpty() ? null : leg(totalS));
        out.put("current_ctl", currentCtl != null ? Py.round(currentCtl, 1) : null);
        out.put("missing", missing);
        out.put("cutoffs", cutoffChecks(swimS, bikeS, runS, t1, t2, cutoffs));
        out.put("note", "Projection at current fitness from your thresholds and recent bike speed. "
                + "Edit thresholds to refine.");
        return out;
    }

    private static List<Map<String, Object>> cutoffChecks(Double swimS, Double bikeS, Double runS,
                                                          int t1, int t2, Map<String, Integer> cutoffs) {
        // Python `cutoffs or {default}` — an empty (falsy) map falls back too, not
        // just null; otherwise c.get("swim") is null → NPE unboxing into int limit.
        Map<String, Integer> c = (cutoffs != null && !cutoffs.isEmpty()) ? cutoffs
                : Map.of("swim", 70 * 60, "bike", 330 * 60, "finish", 510 * 60);
        List<Map<String, Object>> checks = new ArrayList<>();
        addCheck(checks, "Swim", swimS, c.get("swim"));
        Double cumBike = (swimS != null && bikeS != null) ? swimS + t1 + bikeS : null;
        addCheck(checks, "Bike", cumBike, c.get("bike"));
        Double cumFinish = (swimS != null && bikeS != null && runS != null)
                ? swimS + t1 + bikeS + t2 + runS : null;
        addCheck(checks, "Finish", cumFinish, c.get("finish"));
        return checks;
    }

    private static void addCheck(List<Map<String, Object>> checks, String name, Double projected,
                                 int limit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checkpoint", name);
        m.put("limit_s", limit);
        m.put("limit", fmtHms(limit));
        if (projected == null) {
            m.put("projected_s", null);
            m.put("ok", null);
            checks.add(m);
            return;
        }
        double margin = limit - projected;
        m.put("projected_s", Py.roundInt(projected));
        m.put("projected", fmtHms(projected));
        m.put("margin_s", Py.roundInt(margin));
        m.put("margin", (margin < 0 ? "-" : "+") + fmtHms(Math.abs(margin)));
        m.put("ok", margin >= 0);
        checks.add(m);
    }
}
