package io.gamov.irontrainer.nutrition;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.dashboards.RaceReadiness;
import io.gamov.irontrainer.metrics.Metrics.Thresholds;
import io.gamov.irontrainer.metrics.MetricDaily;
import io.gamov.irontrainer.metrics.MetricsWrite;
import io.gamov.irontrainer.plan.PlannedWorkout;
import io.gamov.irontrainer.races.Races;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/** Nutrition vertical — deterministic fueling reads, contract-parity with
 * FastAPI's /api/nutrition/{workout,daily,race-day}. Bearer/iOS surface. */
@Path("/api/nutrition")
public class NutritionResource {

    private static final Logger LOG = Logger.getLogger(NutritionResource.class);

    @Inject
    CurrentAthlete current;

    @Inject
    Races races;

    @GET
    @Path("/workout/{workout_id}")
    public Map<String, Object> workoutFueling(@PathParam("workout_id") int workoutId) {
        int aid = current.require();
        PlannedWorkout w = PlannedWorkout.find("id = ?1 and athleteId = ?2", workoutId, aid)
                .firstResult();
        if (w == null) {
            throw new NotFoundException("Workout not found");
        }
        Athlete a = Athlete.findById(aid);
        LOG.debugf("Nutrition workout fueling: athlete=%d workout=%d", aid, workoutId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workout_id", workoutId);
        out.put("fueling", Nutrition.computeWorkoutFueling(
                w.durationS, w.intensity,
                a == null ? null : a.gelCarbG,
                a == null ? null : a.bodyWeightKg,
                a == null ? null : a.sweatRateLH));
        return out;
    }

    @GET
    @Path("/daily")
    public Map<String, Object> daily() {
        int aid = current.require();
        Athlete a = Athlete.findById(aid);
        LOG.debugf("Nutrition daily: athlete=%d", aid);
        return Nutrition.computeDaily(
                a == null ? null : a.bodyWeightKg,
                a == null ? null : a.weeklyHoursTarget);
    }

    @GET
    @Path("/race-day")
    public Map<String, Object> raceDay() {
        int aid = current.require();
        Athlete a = Athlete.findById(aid);
        Map<String, Object> race = races.effectiveRace(a);
        Map<String, Object> readiness = raceReadinessFor(aid, race);
        LOG.debugf("Nutrition race-day: athlete=%d", aid);
        return Nutrition.computeRaceDayPlan(
                a == null ? null : a.bodyWeightKg,
                a == null ? null : a.gelCarbG,
                a == null ? null : a.sweatRateLH,
                race, readiness);
    }

    /** Race-split projection feeding the fueling timeline — same assembly as
     * RaceReadinessResource / FastAPI nutrition_router._readiness(). */
    private static Map<String, Object> raceReadinessFor(int aid, Map<String, Object> race) {
        List<MetricDaily> metrics = MetricDaily.list("athleteId = ?1 order by date", aid);
        Double currentCtl = metrics.isEmpty() ? null : metrics.get(metrics.size() - 1).ctl;
        Map<String, Integer> cutoffs = new LinkedHashMap<>();
        cutoffs.put("swim", (Integer) race.get("cutoff_swim_s"));
        cutoffs.put("bike", (Integer) race.get("cutoff_bike_s"));
        cutoffs.put("finish", (Integer) race.get("cutoff_finish_s"));
        String distance = (String) race.get("distance");
        List<Activity> acts = Activity.list(
                "athleteId = ?1 and (isDuplicate = 0 or isDuplicate is null) order by startDate", aid);
        Thresholds th = MetricsWrite.thresholds(aid);
        return RaceReadiness.raceReadiness(acts, th, currentCtl, cutoffs, distance);
    }
}
