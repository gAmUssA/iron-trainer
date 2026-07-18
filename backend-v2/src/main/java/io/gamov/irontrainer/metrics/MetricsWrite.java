package io.gamov.irontrainer.metrics;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.metrics.Metrics.DayMetric;
import io.gamov.irontrainer.metrics.Metrics.Thresholds;
import io.gamov.irontrainer.metrics.Metrics.TssResult;
import io.gamov.irontrainer.util.Iso;
import io.gamov.irontrainer.util.PyJson;
import java.time.LocalDate;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/** The metrics WRITE cascade — Java port of repo.save_profile / recompute_tss /
 * rebuild_metrics / store_metrics. Static helpers that run inside the caller's
 * @Transactional context (Panache active-record). The full applyResult cascade
 * lives HERE (not in a resource) so the Strava-sync vertical reuses the exact
 * same recompute+rebuild sequence with no drift. */
public final class MetricsWrite {

    private static final Logger LOG = Logger.getLogger(MetricsWrite.class);

    private MetricsWrite() {}

    /** The apply cascade: write thresholds, then recompute TSS + rebuild the PMC
     * from them. Mirrors repo.apply_test_result's save_profile → recompute_tss →
     * rebuild_metrics. */
    public static void applyResult(int aid, Map<String, Object> result) {
        saveProfile(aid, result);
        recomputeAndRebuild(aid);
    }

    /** athlete_thresholds(): the current athlete's thresholds. */
    public static Thresholds thresholds(int aid) {
        Athlete a = Athlete.findById(aid);
        if (a == null) return new Thresholds(null, null, null, null, null);
        return new Thresholds(a.ftp, a.thresholdHr, a.maxHr, a.thresholdPaceRun, a.cssSwim);
    }

    /** save_profile(result): write the computed threshold fields onto the athlete
     * (only the keys fitness_tests.compute produces), bumping updated_at on any
     * change (iOS delta-sync watches it) — matching repo.save_profile. */
    public static void saveProfile(int aid, Map<String, Object> result) {
        Athlete a = Athlete.findById(aid);
        if (a == null) return;
        boolean changed = false;
        if (result.containsKey("ftp")) { a.ftp = asDouble(result.get("ftp")); changed = true; }
        if (result.containsKey("threshold_hr")) { a.thresholdHr = asInt(result.get("threshold_hr")); changed = true; }
        if (result.containsKey("max_hr")) { a.maxHr = asInt(result.get("max_hr")); changed = true; }
        if (result.containsKey("threshold_pace_run")) { a.thresholdPaceRun = asDouble(result.get("threshold_pace_run")); changed = true; }
        if (result.containsKey("css_swim")) { a.cssSwim = asDouble(result.get("css_swim")); changed = true; }
        if (changed) a.updatedAt = PyJson.utcNowIso();
        // Managed entity — changes flush at commit.
    }

    /** recompute_tss + rebuild_metrics over ONE activity load: recompute every
     * activity's TSS (incl. duplicates, as FastAPI does) from current thresholds,
     * then build the CTL/ATL/TSB series from the NON-duplicate subset and replace
     * metrics_daily. "today" is host-local (matches FastAPI's date.today(); both
     * backends run on the same host, so the series ends on the same day).
     * Reused by the Strava-sync vertical. */
    public static void recomputeAndRebuild(int aid) {
        Thresholds th = thresholds(aid);
        List<Activity> acts = Activity.list("athleteId", aid);
        List<Map.Entry<LocalDate, Double>> pairs = new ArrayList<>();
        for (Activity a : acts) {
            TssResult r = Metrics.computeTss(a.sport,
                    a.movingTime == null ? null : (double) a.movingTime,
                    a.distance, a.weightedPower, a.avgPower, a.avgHr, th);
            a.tss = r.tss();
            a.intensityFactor = r.intensityFactor();
            a.tssMethod = r.method();
            // rebuild_metrics uses list_activities() (non-duplicate only).
            if (a.isDuplicate != null && a.isDuplicate != 0) continue;
            LocalDate day = Iso.parseDate(a.startDate);
            if (day == null) continue;  // Python skips unparseable start_date
            pairs.add(new AbstractMap.SimpleEntry<>(day, a.tss));
        }
        List<DayMetric> days = Metrics.performanceManagement(pairs, LocalDate.now(), null, 0.0, 0.0);
        storeMetrics(aid, days);
    }

    /** recompute_tss() ONLY: re-cost every activity's TSS from current thresholds,
     * WITHOUT rebuilding metrics_daily. Mirrors repo.recompute_tss — used by
     * seed_profile_if_empty, whose caller (Strava sync) rebuilds the PMC afterward
     * from the re-costed TSS. (recomputeAndRebuild stays a single-pass fast path
     * for the apply cascade; this is the deliberate second variant.) */
    public static void recomputeTss(int aid) {
        Thresholds th = thresholds(aid);
        for (Activity a : Activity.<Activity>list("athleteId", aid)) {
            TssResult r = Metrics.computeTss(a.sport,
                    a.movingTime == null ? null : (double) a.movingTime,
                    a.distance, a.weightedPower, a.avgPower, a.avgHr, th);
            a.tss = r.tss();
            a.intensityFactor = r.intensityFactor();
            a.tssMethod = r.method();
        }
    }

    /** rebuild_metrics(): rebuild metrics_daily from the STORED activity TSS
     * (non-duplicate only), without recomputing TSS. Used after de-duplication,
     * which changes the non-duplicate set but not per-activity load. Returns the
     * number of days written. */
    public static int rebuildMetrics(int aid) {
        return rebuildMetrics(aid, Activity.list("athleteId", aid));
    }

    /** As above, but from an already-loaded activity list (e.g. dedup just loaded
     * + mutated the athlete's activities) — filters non-duplicates in memory
     * instead of re-querying. */
    public static int rebuildMetrics(int aid, List<Activity> acts) {
        List<Map.Entry<LocalDate, Double>> pairs = new ArrayList<>();
        for (Activity a : acts) {
            if (a.isDuplicate != null && a.isDuplicate != 0) continue;  // non-duplicate only
            LocalDate day = Iso.parseDate(a.startDate);
            if (day == null) continue;
            pairs.add(new AbstractMap.SimpleEntry<>(day, a.tss == null ? 0.0 : a.tss));
        }
        List<DayMetric> days = Metrics.performanceManagement(pairs, LocalDate.now(), null, 0.0, 0.0);
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

    private static Double asDouble(Object v) {
        return v == null ? null : ((Number) v).doubleValue();
    }

    private static Integer asInt(Object v) {
        return v == null ? null : ((Number) v).intValue();
    }
}
