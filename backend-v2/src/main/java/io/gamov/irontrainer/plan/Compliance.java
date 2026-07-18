package io.gamov.irontrainer.plan;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.util.Iso;
import io.gamov.irontrainer.util.Py;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Planned-vs-actual compliance — faithful port of reconcile.compliance_by_week
 * and reconcile.recent_compliance. Pure functions over the plan's workouts + the
 * athlete's (non-duplicate) activities; no LLM, no I/O. */
public final class Compliance {

    private Compliance() {}

    /** compliance_by_week: planned vs actual per ISO week (Monday start), with
     * completed/skipped/planned counts. Floats rounded to 1 dp (banker's). */
    public static List<Map<String, Object>> byWeek(List<PlannedWorkout> workouts, List<Activity> acts) {
        Map<Long, Double> actTss = tssById(acts);
        Map<Long, Integer> actSecs = secsById(acts);

        // TreeMap → the Python `sorted(weeks)` iteration order.
        Map<LocalDate, double[]> floats = new TreeMap<>();   // [planned_tss, actual_tss, planned_h, actual_h]
        Map<LocalDate, int[]> counts = new TreeMap<>();       // [completed, skipped, planned]
        for (PlannedWorkout w : workouts) {
            LocalDate d = Iso.parseDate(w.date);
            if (d == null) {
                continue;
            }
            LocalDate ws = d.minusDays(d.getDayOfWeek().getValue() - 1L);   // Monday
            double[] f = floats.computeIfAbsent(ws, k -> new double[4]);
            int[] c = counts.computeIfAbsent(ws, k -> new int[3]);
            f[0] += w.plannedTss == null ? 0.0 : w.plannedTss;
            f[2] += (w.durationS == null ? 0 : w.durationS) / 3600.0;
            if ("completed".equals(w.status)) {
                c[0]++;
                f[1] += actTss.getOrDefault(w.matchedActivityId, 0.0);
                f[3] += actSecs.getOrDefault(w.matchedActivityId, 0) / 3600.0;
            } else if ("skipped".equals(w.status)) {
                c[1]++;
            } else {
                c[2]++;
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (LocalDate ws : floats.keySet()) {
            double[] f = floats.get(ws);
            int[] c = counts.get(ws);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("week_start", ws.toString());
            row.put("planned_tss", Py.round(f[0], 1));
            row.put("actual_tss", Py.round(f[1], 1));
            row.put("planned_hours", Py.round(f[2], 1));
            row.put("actual_hours", Py.round(f[3], 1));
            row.put("completed", c[0]);
            row.put("skipped", c[1]);
            row.put("planned", c[2]);
            out.add(row);
        }
        return out;
    }

    /** recent_compliance: compact planned-vs-actual summary over the recent
     * window (default 21 days, inclusive of today). */
    public static Map<String, Object> recent(List<PlannedWorkout> workouts, List<Activity> acts,
                                             LocalDate today, int days) {
        Map<Long, Double> actTss = tssById(acts);
        LocalDate cutoff = today.minusDays(days);

        int plannedN = 0, completedN = 0, skippedN = 0;
        double plannedTss = 0.0, actualTss = 0.0;
        for (PlannedWorkout w : workouts) {
            LocalDate d = Iso.parseDate(w.date);
            if (d == null || d.isBefore(cutoff) || d.isAfter(today)) {
                continue;
            }
            plannedN++;
            plannedTss += w.plannedTss == null ? 0.0 : w.plannedTss;
            if ("completed".equals(w.status)) {
                completedN++;
                actualTss += actTss.getOrDefault(w.matchedActivityId, 0.0);
            } else if ("skipped".equals(w.status)) {
                skippedN++;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window_days", days);
        out.put("planned_sessions", plannedN);
        out.put("completed_sessions", completedN);
        out.put("skipped_sessions", skippedN);
        out.put("completion_rate", plannedN > 0 ? Py.round((double) completedN / plannedN, 2) : null);
        out.put("planned_tss", Py.roundInt(plannedTss));
        out.put("actual_tss", Py.roundInt(actualTss));
        out.put("load_ratio", plannedTss != 0.0 ? Py.round(actualTss / plannedTss, 2) : null);
        return out;
    }

    // {activity id → tss}, mirroring {a["id"]: (a.get("tss") or 0.0)}.
    private static Map<Long, Double> tssById(List<Activity> acts) {
        Map<Long, Double> m = new LinkedHashMap<>();
        for (Activity a : acts) {
            m.put(a.id, a.tss == null ? 0.0 : a.tss);
        }
        return m;
    }

    // {activity id → moving_time seconds}, mirroring {a["id"]: (a.get("moving_time") or 0)}.
    private static Map<Long, Integer> secsById(List<Activity> acts) {
        Map<Long, Integer> m = new LinkedHashMap<>();
        for (Activity a : acts) {
            m.put(a.id, a.movingTime == null ? 0 : a.movingTime);
        }
        return m;
    }
}
