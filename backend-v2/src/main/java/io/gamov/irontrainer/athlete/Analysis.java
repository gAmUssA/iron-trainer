package io.gamov.irontrainer.athlete;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.metrics.MetricsWrite;
import io.gamov.irontrainer.util.Iso;
import io.gamov.irontrainer.util.Py;
import io.gamov.irontrainer.util.PyJson;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Infer athlete thresholds from recent Strava summary history — faithful port of
 * app/analysis.py (infer_profile) + services.seed_profile_if_empty. Deliberately
 * simple heuristics that SEED an empty profile; every value is surfaced for the
 * athlete to confirm. JSON must match FastAPI: round(x,0)/round(x,1) → Py.round
 * (float), round()/int() → integer, `:.0f`/`:.1f` → Py.f0/Py.f1. */
public final class Analysis {

    private Analysis() {}

    static final int THRESHOLD_WINDOW_DAYS = 84;
    static final int AVAILABILITY_WINDOW_DAYS = 56;

    /** infer_profile with the default 84d threshold / 56d availability windows. */
    public static Map<String, Object> inferProfile(List<Activity> activities, LocalDate today) {
        return inferProfile(activities, today, THRESHOLD_WINDOW_DAYS, AVAILABILITY_WINDOW_DAYS);
    }

    public static Map<String, Object> inferProfile(List<Activity> activities, LocalDate today,
            int thresholdWindowDays, int availabilityWindowDays) {
        LocalDate thCutoff = today.minusDays(thresholdWindowDays);
        LocalDate avCutoff = today.minusDays(availabilityWindowDays);

        double bestNp = 0.0;                 // bike normalized power, sustained efforts
        Double fastestRunPace = null;        // sec/km (smaller is faster)
        Double fastestSwimPace = null;       // sec/100m
        int bestThresholdHr = 0;
        int observedMaxHr = 0;
        double availabilitySeconds = 0.0;

        for (Activity a : activities) {
            LocalDate day = Iso.parseDate(a.startDate);   // fromisoformat(...).date(), null on bad
            if (day == null) continue;       // Python: KeyError/ValueError → skip
            String sport = a.sport;
            double moving = a.movingTime == null ? 0 : a.movingTime;   // moving_time or 0
            double distance = a.distance == null ? 0 : a.distance;     // distance or 0
            Double avgHr = a.avgHr;
            Double maxHr = a.maxHr;

            if (maxHr != null && maxHr != 0.0) {                       // if max_hr:
                observedMaxHr = Math.max(observedMaxHr, (int) maxHr.doubleValue());
            }
            if (!day.isBefore(avCutoff)) availabilitySeconds += moving;  // day >= av_cutoff
            if (day.isBefore(thCutoff)) continue;                        // day < th_cutoff

            boolean sustained = moving >= 1200;                          // >= 20 min

            if ("Bike".equals(sport) && sustained) {
                Double np = (a.weightedPower != null && a.weightedPower != 0.0)
                        ? a.weightedPower : a.avgPower;                  // weighted_power or avg_power
                if (np != null && np != 0.0) bestNp = Math.max(bestNp, np);
                if (avgHr != null && avgHr != 0.0) {
                    bestThresholdHr = Math.max(bestThresholdHr, (int) avgHr.doubleValue());
                }
            }
            if ("Run".equals(sport) && sustained && distance > 0) {
                double pace = moving / (distance / 1000.0);             // sec/km
                if (fastestRunPace == null || pace < fastestRunPace) fastestRunPace = pace;
                if (avgHr != null && avgHr != 0.0) {
                    bestThresholdHr = Math.max(bestThresholdHr, (int) avgHr.doubleValue());
                }
            }
            if ("Swim".equals(sport) && distance >= 400) {
                double pace = moving / (distance / 100.0);              // sec/100m
                if (fastestSwimPace == null || pace < fastestSwimPace) fastestSwimPace = pace;
            }
        }

        Map<String, Object> basis = new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        // InferredProfile.as_dict() field order: ftp, threshold_hr, max_hr,
        // threshold_pace_run, css_swim, weekly_hours_target, basis.
        Object ftp = null, thresholdHr = null, maxHr = null,
                thresholdPaceRun = null, cssSwim = null, weeklyHoursTarget = null;

        if (bestNp > 0) {
            ftp = Py.round(bestNp * 0.95, 0);                           // ~95% of best sustained NP
            basis.put("ftp", "95% of best sustained bike NP (" + Py.f0(bestNp)
                    + "W) in last 12 weeks");
        }
        if (fastestRunPace != null && fastestRunPace != 0.0) {
            thresholdPaceRun = Py.round(fastestRunPace * 1.04, 0);      // true threshold ~4% slower
            basis.put("threshold_pace_run", "fastest sustained run pace ("
                    + fmtPaceKm(fastestRunPace) + ") +4%");
        }
        if (fastestSwimPace != null && fastestSwimPace != 0.0) {
            cssSwim = Py.round(fastestSwimPace, 0);
            basis.put("css_swim", "fastest sustained swim pace (" + fmtPace100(fastestSwimPace) + ")");
        }
        if (bestThresholdHr > 0) {
            thresholdHr = (long) bestThresholdHr;
            basis.put("threshold_hr", "max avg HR on sustained efforts (" + bestThresholdHr + " bpm)");
        } else if (observedMaxHr > 0) {
            thresholdHr = Py.roundInt(observedMaxHr * 0.92);
            basis.put("threshold_hr", "~92% of observed max HR (" + observedMaxHr + " bpm)");
        }
        if (observedMaxHr > 0) {
            maxHr = (long) observedMaxHr;
        }
        double weeks = availabilityWindowDays / 7.0;
        double hours = availabilitySeconds / 3600.0;
        if (hours > 0) {
            weeklyHoursTarget = Py.round(hours / weeks, 1);
            basis.put("weekly_hours_target", "avg of " + Py.f1(hours)
                    + "h over last " + (int) weeks + " weeks");
        }

        out.put("ftp", ftp);
        out.put("threshold_hr", thresholdHr);
        out.put("max_hr", maxHr);
        out.put("threshold_pace_run", thresholdPaceRun);
        out.put("css_swim", cssSwim);
        out.put("weekly_hours_target", weeklyHoursTarget);
        out.put("basis", basis);
        return out;
    }

    /** seed_profile_if_empty: infer + persist thresholds ONLY when the athlete
     * has none yet (a first-connect convenience; no-op once any of ftp/threshold_hr/
     * threshold_pace_run/css_swim is set — NOT max_hr/weekly, matching Python).
     * Re-costs activities with the inferred thresholds (recompute_tss) but does
     * NOT rebuild the PMC — the caller (Strava sync / archive import) rebuilds
     * afterward. Returns the inferred map, or null when already seeded. Must run
     * inside the caller's transaction. */
    public static Map<String, Object> seedProfileIfEmpty(int aid, LocalDate today) {
        Athlete a = Athlete.findById(aid);
        if (a == null) return null;
        boolean already = Py.truthy(a.ftp) || Py.truthy(a.thresholdHr)
                || Py.truthy(a.thresholdPaceRun) || Py.truthy(a.cssSwim);
        if (already) return null;
        List<Activity> acts = Activity.list(
                "athleteId = ?1 and (isDuplicate = 0 or isDuplicate is null) order by startDate", aid);
        Map<String, Object> inferred = inferProfile(acts, today);
        saveInferred(a, inferred);          // fill blanks only
        MetricsWrite.recomputeTss(aid);     // re-cost only; caller rebuilds the PMC
        return inferred;
    }

    /** save_profile for inferred thresholds: sets only the non-null values
     * (never clears a field inference couldn't compute) and bumps updated_at.
     * The entity is managed inside the caller's transaction, so field mutation
     * flushes on commit — no explicit persist() needed. */
    public static void saveInferred(Athlete a, Map<String, Object> inferred) {
        if (a == null) return;
        boolean changed = false;
        Object v;
        if ((v = inferred.get("ftp")) != null) { a.ftp = ((Number) v).doubleValue(); changed = true; }
        if ((v = inferred.get("threshold_hr")) != null) { a.thresholdHr = ((Number) v).intValue(); changed = true; }
        if ((v = inferred.get("max_hr")) != null) { a.maxHr = ((Number) v).intValue(); changed = true; }
        if ((v = inferred.get("threshold_pace_run")) != null) { a.thresholdPaceRun = ((Number) v).doubleValue(); changed = true; }
        if ((v = inferred.get("css_swim")) != null) { a.cssSwim = ((Number) v).doubleValue(); changed = true; }
        if ((v = inferred.get("weekly_hours_target")) != null) { a.weeklyHoursTarget = ((Number) v).doubleValue(); changed = true; }
        if (changed) {
            a.updatedAt = PyJson.utcNowIso();   // _now_iso(): shared UTC ISO, matches every writer
        }
    }

    private static String fmtPaceKm(double secPerKm) {
        int v = (int) secPerKm;                      // int() truncates toward zero
        return String.format(Locale.ROOT, "%d:%02d/km", v / 60, v % 60);
    }

    private static String fmtPace100(double secPer100) {
        int v = (int) secPer100;
        return String.format(Locale.ROOT, "%d:%02d/100m", v / 60, v % 60);
    }
}
