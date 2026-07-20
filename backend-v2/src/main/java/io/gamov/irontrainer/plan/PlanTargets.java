package io.gamov.irontrainer.plan;

import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.util.PyJson;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/** refresh_future_plan_targets: re-derive workout targets for every FUTURE week
 * of the active plan from the athlete's CURRENT thresholds (and re-cost fueling),
 * so the plan keeps up as fitness improves. Past + current-week workouts (and
 * their matched history) are never touched; week volumes/phases are preserved —
 * only the prescriptions inside future workouts move. Port of
 * planning.service.refresh_future_plan_targets. */
@ApplicationScoped
public class PlanTargets {

    private static final Logger LOG = Logger.getLogger(PlanTargets.class);

    /** @return the number of future weeks refreshed (0 when there's no active plan). */
    @SuppressWarnings("unchecked")
    public int refreshFuture(int aid, LocalDate today) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Plan plan = Plan.activeFor(aid);
            if (plan == null) {
                return 0;
            }
            Athlete a = Athlete.findById(aid);
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("ftp", a == null ? null : a.ftp);
            profile.put("threshold_hr", a == null ? null : a.thresholdHr);
            profile.put("max_hr", a == null ? null : a.maxHr);
            profile.put("threshold_pace_run", a == null ? null : a.thresholdPaceRun);
            profile.put("css_swim", a == null ? null : a.cssSwim);
            Double bw = a == null ? null : a.bodyWeightKg;
            Double gc = a == null ? null : a.gelCarbG;
            Double sr = a == null ? null : a.sweatRateLH;

            String currentWeekStart = PlanTemplate.mondayOf(today).toString();
            Object parsed = (plan.weeksJson == null || plan.weeksJson.isBlank())
                    ? List.of() : PyJson.loads(plan.weeksJson);
            int refreshed = 0;
            for (Object o : (List<Object>) parsed) {
                if (!(o instanceof Map<?, ?> wm)) {
                    continue;
                }
                Map<String, Object> week = (Map<String, Object>) wm;
                // Python does week["week_start"] — a missing/non-string key raises
                // (KeyError/TypeError), which update_profile's try/except turns into
                // plan_weeks_refreshed=0; mirror that by aborting the whole refresh.
                if (!(week.get("week_start") instanceof String ws)) {
                    throw new IllegalStateException("plan week missing week_start");
                }
                // ISO dates compare lexicographically = chronologically. Skip
                // history and the in-flight week (week_start <= this Monday).
                if (ws.compareTo(currentWeekStart) <= 0) {
                    continue;
                }
                List<Map<String, Object>> workouts = PlanTemplate.expandWeek(week, profile);
                workouts = PlanValidator.capWeekWorkouts(workouts).workouts();
                PlanResource.applyFueling(workouts, bw, gc, sr);
                String weekEnd = LocalDate.parse(ws).plusDays(6).toString();
                PlannedWorkout.replaceWeek(aid, plan.id, ws, weekEnd, workouts);
                refreshed++;
            }
            if (refreshed > 0) {
                LOG.infof("Refreshed targets for %d future week(s) from current thresholds.", refreshed);
            }
            return refreshed;
        });
    }
}
