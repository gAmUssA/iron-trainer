package io.gamov.irontrainer.metrics;

import io.gamov.irontrainer.util.Py;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Training-load math — faithful port of app/metrics.py (the write side:
 * per-activity TSS and the CTL/ATL/TSB series). Pure functions; the values it
 * produces are stored to activities.tss and metrics_daily, so every round()
 * routes through Py (banker's rounding) to stay byte-identical to FastAPI.
 * normalized_power (Strava-import only) is intentionally not ported here. */
public final class Metrics {

    private Metrics() {}

    public static final double DEFAULT_IF = 0.70;
    public static final int CTL_TIME_CONSTANT = 42;   // days (chronic / "fitness")
    public static final int ATL_TIME_CONSTANT = 7;    // days (acute / "fatigue")

    /** Athlete thresholds; any may be null. */
    public record Thresholds(Double ftp, Integer thresholdHr, Integer maxHr,
                             Double thresholdPaceRun, Double cssSwim) {}

    /** One day of the performance-management series. */
    public record DayMetric(LocalDate day, double tss, double ctl, double atl, double tsb) {}

    public record IfResult(double intensityFactor, String method) {}

    public record TssResult(double tss, double intensityFactor, String method) {}

    private static final Map<String, String> SPORT_MAP = Map.of(
        "Run", "Run", "TrailRun", "Run", "VirtualRun", "Run",
        "Ride", "Bike", "VirtualRide", "Bike", "GravelRide", "Bike",
        "MountainBikeRide", "Bike", "EBikeRide", "Bike", "Swim", "Swim");

    public static String normalizeSport(String stravaType) {
        return SPORT_MAP.getOrDefault(stravaType == null ? "" : stravaType, "Other");
    }

    /** Normalized Power from a ~1 Hz power stream: 30s rolling average → 4th
     * power → mean → 4th root, rounded to an int. Null without enough data.
     * Port of metrics.normalized_power (used by the Strava stream import). */
    public static Long normalizedPower(List<Double> samples, double dtS) {
        List<Double> vals = new ArrayList<>();
        for (Double p : samples) if (p != null) vals.add(p);
        int window = Math.max(1, (int) Py.round(30.0 / dtS, 0));
        if (vals.size() < Math.max(30, window)) return null;
        Deque<Double> win = new ArrayDeque<>();
        List<Double> rolled = new ArrayList<>();
        for (Double p : vals) {
            win.addLast(p);
            if (win.size() > window) win.removeFirst();
            if (win.size() == window) {
                double sum = 0;
                for (double w : win) sum += w;
                rolled.add(Math.pow(sum / window, 4));
            }
        }
        if (rolled.isEmpty()) return null;
        double sum = 0;
        for (double r : rolled) sum += r;
        return Py.roundInt(Math.pow(sum / rolled.size(), 0.25));
    }

    /** Guard against absurd values from noisy summary data. */
    static double clampIf(double value) {
        return Math.max(0.3, Math.min(value, 1.25));
    }

    public static IfResult intensityFactor(String sport, Double movingTime, Double distance,
            Double weightedPower, Double avgPower, Double avgHr, Thresholds th) {
        // 1) Power (bike): IF = NP / FTP
        if ("Bike".equals(sport) && Py.truthy(th.ftp())) {
            Double power = Py.truthy(weightedPower) ? weightedPower : avgPower;
            if (Py.truthy(power)) return new IfResult(clampIf(power / th.ftp()), "power");
        }
        // 2) Pace (run): IF = threshold_pace / actual_pace
        if ("Run".equals(sport) && Py.truthy(th.thresholdPaceRun())
                && Py.truthy(movingTime) && Py.truthy(distance)) {
            double km = distance / 1000.0;
            if (km > 0) {
                double actualPace = movingTime / km;  // sec/km
                if (actualPace > 0) return new IfResult(clampIf(th.thresholdPaceRun() / actualPace), "pace");
            }
        }
        // 3) Pace (swim): IF = css_pace / actual_pace_per_100
        if ("Swim".equals(sport) && Py.truthy(th.cssSwim())
                && Py.truthy(movingTime) && Py.truthy(distance)) {
            double hundreds = distance / 100.0;
            if (hundreds > 0) {
                double actualPace = movingTime / hundreds;  // sec/100m
                if (actualPace > 0) return new IfResult(clampIf(th.cssSwim() / actualPace), "pace");
            }
        }
        // 4) Heart rate: IF ≈ avg_hr / threshold_hr
        if (Py.truthy(avgHr) && Py.truthy(th.thresholdHr())) {
            return new IfResult(clampIf(avgHr / th.thresholdHr()), "hr");
        }
        // 5) Duration-only fallback.
        return new IfResult(DEFAULT_IF, "duration");
    }

    public static TssResult computeTss(String sport, Double movingTime, Double distance,
            Double weightedPower, Double avgPower, Double avgHr, Thresholds th) {
        if (!Py.truthy(movingTime) || movingTime <= 0) {
            return new TssResult(0.0, 0.0, "none");
        }
        IfResult f = intensityFactor(sport, movingTime, distance, weightedPower, avgPower, avgHr, th);
        double hours = movingTime / 3600.0;
        double tss = hours * (f.intensityFactor() * f.intensityFactor()) * 100.0;
        return new TssResult(Py.round(tss, 1), Py.round(f.intensityFactor(), 3), f.method());
    }

    /** Sum TSS by calendar day. */
    static Map<LocalDate, Double> dailyTss(List<Map.Entry<LocalDate, Double>> activities) {
        Map<LocalDate, Double> totals = new TreeMap<>();
        for (Map.Entry<LocalDate, Double> e : activities) {
            totals.merge(e.getKey(), e.getValue() == null ? 0.0 : e.getValue(), Double::sum);
        }
        return totals;
    }

    /** CTL/ATL/TSB for every day from start..end. TSB (form) uses yesterday's
     * CTL−ATL — the TrainingPeaks convention. Mirrors performance_management. */
    public static List<DayMetric> performanceManagement(
            List<Map.Entry<LocalDate, Double>> activities, LocalDate end,
            LocalDate start, double ctlSeed, double atlSeed) {
        Map<LocalDate, Double> totals = dailyTss(activities);
        if (totals.isEmpty() && start == null) return List.of();
        LocalDate first = start != null ? start : ((TreeMap<LocalDate, Double>) totals).firstKey();
        if (first.isAfter(end)) return List.of();

        double ctlAlpha = 1.0 / CTL_TIME_CONSTANT;
        double atlAlpha = 1.0 / ATL_TIME_CONSTANT;
        List<DayMetric> out = new ArrayList<>();
        double ctl = ctlSeed, atl = atlSeed;
        for (LocalDate day = first; !day.isAfter(end); day = day.plusDays(1)) {
            double prevCtl = ctl, prevAtl = atl;
            double tss = totals.getOrDefault(day, 0.0);
            ctl = prevCtl + (tss - prevCtl) * ctlAlpha;
            atl = prevAtl + (tss - prevAtl) * atlAlpha;
            double tsb = prevCtl - prevAtl;  // form uses yesterday's values
            out.add(new DayMetric(day, Py.round(tss, 1), Py.round(ctl, 1),
                    Py.round(atl, 1), Py.round(tsb, 1)));
        }
        return out;
    }
}
