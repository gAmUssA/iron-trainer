package io.gamov.irontrainer.metrics;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.metrics.Metrics.DayMetric;
import io.gamov.irontrainer.metrics.Metrics.Thresholds;
import io.gamov.irontrainer.metrics.Metrics.TssResult;
import java.time.LocalDate;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/** The metrics WRITE cascade — Java port of repo.recompute_tss / rebuild_metrics
 * / store_metrics / save_profile. Static helpers that run inside the caller's
 * @Transactional context (Panache active-record). Shared by the fitness-test
 * apply endpoint and, later, Strava sync. */
public final class MetricsWrite {

    private static final Logger LOG = Logger.getLogger(MetricsWrite.class);

    private MetricsWrite() {}

    /** athlete_thresholds(): the current athlete's thresholds. */
    public static Thresholds thresholds(int aid) {
        Athlete a = Athlete.findById(aid);
        if (a == null) return new Thresholds(null, null, null, null, null);
        return new Thresholds(a.ftp, a.thresholdHr, a.maxHr, a.thresholdPaceRun, a.cssSwim);
    }

    /** save_profile(result): write the computed threshold fields onto the athlete
     * (only the keys present in `result`, as fitness_tests.compute produces). */
    public static void saveProfile(int aid, Map<String, Object> result) {
        Athlete a = Athlete.findById(aid);
        if (a == null) return;
        if (result.containsKey("ftp")) a.ftp = asDouble(result.get("ftp"));
        if (result.containsKey("threshold_hr")) a.thresholdHr = asInt(result.get("threshold_hr"));
        if (result.containsKey("max_hr")) a.maxHr = asInt(result.get("max_hr"));
        if (result.containsKey("threshold_pace_run")) a.thresholdPaceRun = asDouble(result.get("threshold_pace_run"));
        if (result.containsKey("css_swim")) a.cssSwim = asDouble(result.get("css_swim"));
        // Managed entity — changes flush at commit.
    }

    /** recompute_tss(): recompute TSS for EVERY activity (incl. duplicates, as
     * FastAPI does) from the current thresholds, writing the values back. */
    public static int recomputeTss(int aid) {
        Thresholds th = thresholds(aid);
        List<Activity> acts = Activity.list("athleteId", aid);
        for (Activity a : acts) {
            TssResult r = Metrics.computeTss(a.sport,
                    a.movingTime == null ? null : (double) a.movingTime,
                    a.distance, a.weightedPower, a.avgPower, a.avgHr, th);
            a.tss = r.tss();
            a.intensityFactor = r.intensityFactor();
            a.tssMethod = r.method();
        }
        return acts.size();
    }

    /** rebuild_metrics(today): (day, tss) from NON-duplicate activities →
     * performance_management → store_metrics. */
    public static int rebuildMetrics(int aid, LocalDate today) {
        List<Activity> acts = Activity.list(
                "athleteId = ?1 and (isDuplicate = 0 or isDuplicate is null)", aid);
        List<Map.Entry<LocalDate, Double>> pairs = new ArrayList<>();
        for (Activity a : acts) {
            LocalDate day = parseDay(a.startDate);
            if (day == null) continue;  // Python skips unparseable start_date
            pairs.add(new AbstractMap.SimpleEntry<>(day, a.tss == null ? 0.0 : a.tss));
        }
        List<DayMetric> days = Metrics.performanceManagement(pairs, today, null, 0.0, 0.0);
        storeMetrics(aid, days);
        return days.size();
    }

    /** store_metrics(days): replace the athlete's metrics_daily with `days`. */
    public static void storeMetrics(int aid, List<DayMetric> days) {
        MetricDaily.delete("athleteId", aid);
        for (DayMetric d : days) {
            MetricDaily m = new MetricDaily();
            m.athleteId = aid;
            m.date = d.day().toString();
            m.tss = d.tss();
            m.ctl = d.ctl();
            m.atl = d.atl();
            m.tsb = d.tsb();
            m.persist();
        }
        LOG.debugf("store_metrics: athlete=%d days=%d", aid, days.size());
    }

    /** Python: datetime.fromisoformat(start_date.replace("Z","+00:00")).date() —
     * which is just the calendar-date part (no tz conversion). */
    private static LocalDate parseDay(String startDate) {
        if (startDate == null || startDate.length() < 10) return null;
        try {
            return LocalDate.parse(startDate.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    private static Double asDouble(Object v) {
        return v == null ? null : ((Number) v).doubleValue();
    }

    private static Integer asInt(Object v) {
        return v == null ? null : ((Number) v).intValue();
    }
}
