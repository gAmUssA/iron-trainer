package io.gamov.irontrainer.nutrition;

import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.plan.PlannedWorkout;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jboss.logging.Logger;

/** Nutrition vertical — deterministic fueling reads, contract-parity with
 * FastAPI's /api/nutrition/{workout,daily}. Bearer/iOS surface. */
@Path("/api/nutrition")
public class NutritionResource {

    private static final Logger LOG = Logger.getLogger(NutritionResource.class);

    @Inject
    CurrentAthlete current;

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
}
