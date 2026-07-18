package io.gamov.irontrainer.dashboards;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.metrics.MetricDaily;
import io.gamov.irontrainer.util.Py;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Trend insights — port of backend/app/insights.py: rolling trendline + slope
 * verdict per sport, weekly intensity mix, personal records, CTL race-day
 * trajectory, freshness. Pure functions over stored summaries. */
public final class Insights {

    private Insights() {}

    static final int ROLLING_DAYS = 28;
    static final int SLOPE_DAYS = 84;
    static final double STEADY_PCT = 1.5;

    // (name, lo, hi) IF buckets — session-average intensity.
    private static final String[][] IF_BUCKET_NAMES = {
        {"easy"}, {"endurance"}, {"tempo"}, {"hard"}
    };
    private static final double[] IF_LO = {0.0, 0.70, 0.85, 0.95};
    private static final double[] IF_HI = {0.70, 0.85, 0.95, 10.0};

    /** insights._day: date.fromisoformat(str(v)[:10]) — first 10 chars only. */
    static LocalDate day(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(v.substring(0, Math.min(10, v.length())));
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDate monday(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static double num(Object o) {
        return ((Number) o).doubleValue();
    }

    /** Trailing mean over the previous window_days for each point (single-pass
     * sliding window; points arrive date-ordered). */
    static List<Map<String, Object>> rollingMean(List<Map<String, Object>> points, String key,
                                                  int windowDays) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Long> wDates = new ArrayList<>();
        List<Double> wVals = new ArrayList<>();
        double total = 0.0;
        int start = 0;
        for (Map<String, Object> p : points) {
            String iso = (String) p.get("date");
            LocalDate d = LocalDate.parse(iso);
            Object val = p.get(key);
            if (val != null) {
                wDates.add(d.toEpochDay());
                wVals.add(num(val));
                total += num(val);
            }
            long lo = d.minusDays(windowDays - 1L).toEpochDay();
            while (start < wDates.size() && wDates.get(start) < lo) {
                total -= wVals.get(start);
                start++;
            }
            int n = wDates.size() - start;
            if (n > 0) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("date", iso);
                o.put("value", Py.round(total / n, 1));
                out.add(o);
            }
        }
        return out;
    }

    /** Least-squares % change of `key` across the last `days` days, or null with
     * fewer than 4 points / degenerate fit. */
    static Double slopePct(List<Map<String, Object>> points, String key, LocalDate today) {
        LocalDate lo = today.minusDays(SLOPE_DAYS);
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (Map<String, Object> p : points) {
            LocalDate d = LocalDate.parse((String) p.get("date"));
            Object v = p.get(key);
            if (!d.isBefore(lo) && v != null) {   // d >= lo
                xs.add((double) (d.toEpochDay() - lo.toEpochDay()));
                ys.add(num(v));
            }
        }
        if (xs.size() < 4) {
            return null;
        }
        int n = xs.size();
        double mx = 0;
        double my = 0;
        for (int i = 0; i < n; i++) {
            mx += xs.get(i);
            my += ys.get(i);
        }
        mx /= n;
        my /= n;
        double denom = 0;
        for (double x : xs) {
            denom += (x - mx) * (x - mx);
        }
        if (denom == 0 || my == 0) {
            return null;
        }
        double cov = 0;
        double minx = xs.get(0);
        double maxx = xs.get(0);
        for (int i = 0; i < n; i++) {
            cov += (xs.get(i) - mx) * (ys.get(i) - my);
            minx = Math.min(minx, xs.get(i));
            maxx = Math.max(maxx, xs.get(i));
        }
        double slope = cov / denom;
        double span = maxx - minx;
        if (span == 0) {
            span = 1;
        }
        return Py.round(slope * span / my * 100, 1);
    }

    private static String verdict(Double changePct, boolean higherIsBetter) {
        if (changePct == null) {
            return "insufficient data";
        }
        if (Math.abs(changePct) <= STEADY_PCT) {
            return "steady";
        }
        boolean improving = higherIsBetter ? changePct > 0 : changePct < 0;
        return improving ? "improving" : "declining";
    }

    /** Per-sport rolling trendline + regression verdict. */
    static Map<String, Object> sportInsights(Map<String, List<Map<String, Object>>> trends,
                                             LocalDate today) {
        // sport: {primaryKey, primaryHib, fallbackKey (null if none), fallbackHib}
        String[][] spec = {
            {"Bike", "ef", "true", "power", "true"},
            {"Run", "ef", "true", "pace", "false"},
            {"Swim", "pace", "false", null, null},
        };
        Map<String, String> valueKey = Map.of("Bike", "power", "Run", "pace", "Swim", "pace");
        Map<String, Object> out = new LinkedHashMap<>();
        for (String[] s : spec) {
            String sport = s[0];
            String pk = s[1];
            boolean phib = Boolean.parseBoolean(s[2]);
            String fk = s[3];
            String key = pk;
            boolean hib = phib;
            List<Map<String, Object>> pts = trends.getOrDefault(sport, List.of());
            if (fk != null) {
                long withPk = pts.stream().filter(p -> p.get(pk) != null).count();
                if (withPk < 4) {
                    key = fk;
                    hib = Boolean.parseBoolean(s[4]);
                }
            }
            Double change = slopePct(pts, key, today);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("metric", key);
            o.put("change_pct", change);
            o.put("verdict", verdict(change, hib));
            o.put("rolling", rollingMean(pts, valueKey.get(sport), ROLLING_DAYS));
            o.put("rolling_ef", "Swim".equals(sport) ? new ArrayList<>()
                    : rollingMean(pts, "ef", ROLLING_DAYS));
            out.put(sport, o);
        }
        return out;
    }

    /** Weekly hours per IF bucket over the last `weeks` weeks (default 12). */
    static List<Map<String, Object>> intensityMix(List<Activity> activities, LocalDate today) {
        int weeks = 12;
        LocalDate start = monday(today).minusWeeks(weeks - 1L);
        Map<LocalDate, Map<String, Double>> rows = new LinkedHashMap<>();
        for (Activity a : activities) {
            LocalDate d = day(a.startDate);
            if (d == null || d.isBefore(start)) {
                continue;
            }
            double hours = (a.movingTime != null ? a.movingTime : 0) / 3600.0;
            if (hours <= 0) {
                continue;
            }
            String bucket = "unknown";
            Double ifv = a.intensityFactor;
            if (Py.truthy(ifv)) {   // IF 0.0 is falsy → "unknown", not "easy"
                for (int i = 0; i < IF_LO.length; i++) {
                    if (ifv >= IF_LO[i] && ifv < IF_HI[i]) {
                        bucket = IF_BUCKET_NAMES[i][0];
                        break;
                    }
                }
            }
            rows.computeIfAbsent(monday(d), k -> freshBuckets()).merge(bucket, hours, Double::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate end = monday(today);
        for (LocalDate wk = start; !wk.isAfter(end); wk = wk.plusWeeks(1)) {
            Map<String, Double> r = rows.getOrDefault(wk, freshBuckets());
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("week_start", wk.toString());
            for (Map.Entry<String, Double> e : r.entrySet()) {
                o.put(e.getKey(), Py.round(e.getValue(), 2));
            }
            out.add(o);
        }
        return out;
    }

    private static Map<String, Double> freshBuckets() {
        Map<String, Double> m = new LinkedHashMap<>();
        for (String[] b : IF_BUCKET_NAMES) {
            m.put(b[0], 0.0);
        }
        m.put("unknown", 0.0);
        return m;
    }

    /** Bests derivable from summaries, with duration/distance floors. */
    static Map<String, Object> personalRecords(List<Activity> activities) {
        Map<String, Object> bestPower = null;
        Map<String, Object> fastestRun = null;
        Map<String, Object> fastestSwim = null;
        Map<String, Object> longestRide = null;
        Map<String, Object> longestRun = null;
        for (Activity a : activities) {
            LocalDate d = day(a.startDate);
            if (d == null) {
                continue;
            }
            String sport = a.sport;
            int moving = a.movingTime != null ? a.movingTime : 0;
            double dist = a.distance != null ? a.distance : 0.0;
            if ("Bike".equals(sport)) {
                Double power = Py.truthy(a.weightedPower) ? a.weightedPower : a.avgPower;
                if (Py.truthy(power) && moving >= 2400
                        && (bestPower == null || power > num(bestPower.get("value")))) {
                    bestPower = ref(d, a.name, Py.roundInt(power));
                }
                if (dist != 0 && (longestRide == null || dist > num(longestRide.get("value")))) {
                    longestRide = ref(d, a.name, Py.roundInt(dist));
                }
            } else if ("Run".equals(sport) && dist > 0 && moving > 0) {
                double pace = moving / (dist / 1000.0);
                if (dist >= 5000 && (fastestRun == null || pace < num(fastestRun.get("value")))) {
                    fastestRun = ref(d, a.name, Py.roundInt(pace));
                }
                if (longestRun == null || dist > num(longestRun.get("value"))) {
                    longestRun = ref(d, a.name, Py.roundInt(dist));
                }
            } else if ("Swim".equals(sport) && dist >= 1000 && moving > 0) {
                double pace = moving / (dist / 100.0);
                if (fastestSwim == null || pace < num(fastestSwim.get("value"))) {
                    fastestSwim = ref(d, a.name, Py.roundInt(pace));
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bike_best_power_40min", bestPower);
        out.put("run_fastest_pace_5k", fastestRun);
        out.put("swim_fastest_pace_1k", fastestSwim);
        out.put("longest_ride_m", longestRide);
        out.put("longest_run_m", longestRun);
        return out;
    }

    private static Map<String, Object> ref(LocalDate d, String name, long value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", d.toString());
        m.put("name", name);
        m.put("value", value);
        return m;
    }

    /** Current CTL, 4-week ramp, straight-line projection to race day. */
    static Map<String, Object> ctlTrajectory(List<MetricDaily> metrics, LocalDate raceDate,
                                             LocalDate today) {
        if (metrics.isEmpty()) {
            return null;
        }
        List<MetricDaily> rows = new ArrayList<>();
        for (MetricDaily m : metrics) {
            if (m.ctl != null && day(m.date) != null) {
                rows.add(m);
            }
        }
        if (rows.isEmpty()) {
            return null;
        }
        MetricDaily last = rows.get(rows.size() - 1);
        double current = last.ctl;
        LocalDate d28 = day(last.date).minusDays(28);
        MetricDaily pastLast = null;
        for (MetricDaily m : rows) {
            if (!day(m.date).isAfter(d28)) {   // day <= d28
                pastLast = m;
            }
        }
        Double rampPerWeek = pastLast != null
                ? Py.round((current - pastLast.ctl) / 4.0, 1) : null;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("current", Py.round(current, 1));
        out.put("ramp_per_week", rampPerWeek);
        if (raceDate != null && raceDate.isAfter(today) && rampPerWeek != null) {
            double weeksLeft = ChronoUnit.DAYS.between(today, raceDate) / 7.0;
            out.put("race_date", raceDate.toString());
            out.put("weeks_to_race", Py.round(weeksLeft, 1));
            double projection = Py.round(current + rampPerWeek * weeksLeft, 1);
            out.put("race_day_projection", projection);
            List<Map<String, Object>> pts = new ArrayList<>();
            for (LocalDate d = today; !d.isAfter(raceDate); d = d.plusWeeks(1)) {
                double w = ChronoUnit.DAYS.between(today, d) / 7.0;
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("date", d.toString());
                pt.put("ctl", Py.round(current + rampPerWeek * w, 1));
                pts.add(pt);
            }
            if (!pts.isEmpty() && !pts.get(pts.size() - 1).get("date").equals(raceDate.toString())) {
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("date", raceDate.toString());
                pt.put("ctl", projection);
                pts.add(pt);
            }
            out.put("projection", pts);
        }
        return out;
    }

    /** When the data was last fed. */
    static Map<String, Object> freshness(List<Activity> activities, LocalDate today) {
        LocalDate last = null;
        for (Activity a : activities) {
            LocalDate d = day(a.startDate);
            if (d != null && (last == null || d.isAfter(last))) {
                last = d;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (last == null) {
            out.put("last_activity", null);
            out.put("days_stale", null);
        } else {
            out.put("last_activity", last.toString());
            out.put("days_stale", (int) ChronoUnit.DAYS.between(last, today));
        }
        return out;
    }

    /** build: everything the Trends page needs beyond the raw per-activity points. */
    public static Map<String, Object> build(List<Activity> activities,
                                            Map<String, List<Map<String, Object>>> trends,
                                            List<MetricDaily> metrics, LocalDate raceDate,
                                            LocalDate today) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sports", sportInsights(trends, today));
        out.put("intensity_weeks", intensityMix(activities, today));
        out.put("prs", personalRecords(activities));
        out.put("ctl_trajectory", ctlTrajectory(metrics, raceDate, today));
        out.put("freshness", freshness(activities, today));
        return out;
    }
}
