package io.gamov.irontrainer.plan;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.util.Iso;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Match completed activities to planned workouts — faithful port of
 * reconcile.match_workouts. Sets each PAST planned workout to completed (linking
 * the fulfilling activity) or skipped; future workouts stay planned. One activity
 * fulfils at most one workout. Mutates the loaded PlannedWorkout entities, so it
 * MUST run inside a transaction (Panache flushes the status changes on commit). */
public final class Reconcile {

    private Reconcile() {}

    // Planned sport → activity sports that can fulfil it. Brick uses an ORDERED
    // list (Bike then Run) so the candidate collection order is deterministic
    // (Python uses a set — order-ambiguous only on an exact day+TSS tie).
    private static final Map<String, List<String>> MATCHES = Map.of(
            "Swim", List.of("Swim"),
            "Bike", List.of("Bike"),
            "Run", List.of("Run"),
            "Brick", List.of("Bike", "Run"));
    private static final Set<String> TRACKED = MATCHES.keySet();

    public static Map<String, Object> matchWorkouts(int aid, int planId, LocalDate today) {
        // forPlan orders by (date, id) — the same order as Python's
        // sorted(get_workouts, key=date) (stable over the date,id read), so the
        // greedy `used` assignment matches.
        List<PlannedWorkout> workouts = PlannedWorkout.forPlan(aid, planId);
        List<Activity> acts = Activity.list(
                "athleteId = ?1 and (isDuplicate = 0 or isDuplicate is null) order by startDate", aid);
        Map<String, List<Activity>> bySport = new LinkedHashMap<>();
        for (Activity a : acts) {
            bySport.computeIfAbsent(a.sport, k -> new ArrayList<>()).add(a);
        }

        Set<Long> used = new HashSet<>();
        int completed = 0, skipped = 0, planned = 0;
        for (PlannedWorkout w : workouts) {
            LocalDate wdate = Iso.parseDate(w.date);
            if (wdate == null) {
                continue;
            }
            if (wdate.isAfter(today)) {
                if (!"planned".equals(w.status)) {
                    w.status = "planned";
                    w.matchedActivityId = null;
                }
                planned++;
                continue;
            }
            Activity match = bestMatch(w, wdate, bySport, used);
            if (match != null) {
                used.add(match.id);
                w.status = "completed";
                w.matchedActivityId = match.id;
                completed++;
            } else if (TRACKED.contains(w.sport)) {
                w.status = "skipped";
                w.matchedActivityId = null;
                skipped++;
            }
            // untracked sports (e.g. Strength) keep their current status
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("completed", completed);
        out.put("skipped", skipped);
        out.put("upcoming", planned);
        return out;
    }

    /** The nearest unused activity (within ±1 day) that can fulfil the workout —
     * nearest day, then biggest TSS. Mirrors reconcile._best_match. */
    private static Activity bestMatch(PlannedWorkout w, LocalDate wdate,
                                      Map<String, List<Activity>> bySport, Set<Long> used) {
        List<String> wanted = MATCHES.get(w.sport);
        if (wanted == null) {
            return null;
        }
        Activity best = null;
        int bestDiff = Integer.MAX_VALUE;
        double bestNegTss = Double.POSITIVE_INFINITY;
        for (String sport : wanted) {
            for (Activity a : bySport.getOrDefault(sport, List.of())) {
                if (used.contains(a.id)) {
                    continue;
                }
                LocalDate adate = Iso.parseDate(a.startDate);
                if (adate == null) {
                    continue;
                }
                int diff = Math.abs((int) (adate.toEpochDay() - wdate.toEpochDay()));
                if (diff > 1) {
                    continue;
                }
                double negTss = -(a.tss == null ? 0.0 : a.tss);
                // sort key (diff, -tss); strictly-less keeps the first on a tie
                // (stable), matching Python's sorted()[0].
                if (diff < bestDiff || (diff == bestDiff && negTss < bestNegTss)) {
                    best = a;
                    bestDiff = diff;
                    bestNegTss = negTss;
                }
            }
        }
        return best;
    }
}
