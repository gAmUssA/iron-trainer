package io.gamov.irontrainer.plan;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.util.PyJson;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/** Plan vertical (read side) — GET /api/plan, contract-parity with FastAPI
 * plan_router.get_plan (repo.get_active_plan + repo.get_workouts). The write
 * endpoints (generate/checkin/replan/reconcile) are separate slices. */
@Path("/api/plan")
public class PlanResource {

    private static final Logger LOG = Logger.getLogger(PlanResource.class);

    @Inject
    CurrentAthlete current;

    /** GET /api/plan — the active plan + its workouts, or {plan:null, workouts:[]}
     * when the athlete has no active plan. Mirrors repo._plan_dict /
     * repo._workout_dict (weeks_json→weeks, structure_json→steps parsed). */
    @GET
    public Map<String, Object> getPlan() {
        int aid = current.require();
        LOG.debugf("Plan read: athlete=%d", aid);
        Plan plan = Plan.activeFor(aid);
        Map<String, Object> out = new LinkedHashMap<>();
        if (plan == null) {
            out.put("plan", null);
            out.put("workouts", List.of());
            return out;
        }
        out.put("plan", planDict(plan));
        out.put("workouts", workouts(plan.id, aid));
        return out;
    }

    /** GET /api/plan/compliance — planned-vs-actual per week + a recent-window
     * summary, or {weeks:[], recent:null} with no active plan. Mirrors
     * plan_router.compliance (reconcile.compliance_by_week + recent_compliance). */
    @GET
    @Path("/compliance")
    public Map<String, Object> compliance() {
        int aid = current.require();
        LOG.debugf("Plan compliance: athlete=%d", aid);
        Plan plan = Plan.activeFor(aid);
        Map<String, Object> out = new LinkedHashMap<>();
        if (plan == null) {
            out.put("weeks", List.of());
            out.put("recent", null);
            return out;
        }
        List<PlannedWorkout> workouts = PlannedWorkout.forPlan(aid, plan.id);
        // repo.list_activities() default: non-duplicate only.
        List<Activity> acts = Activity.list(
                "athleteId = ?1 and (isDuplicate = 0 or isDuplicate is null) order by startDate", aid);
        out.put("weeks", Compliance.byWeek(workouts, acts));
        out.put("recent", Compliance.recent(workouts, acts, LocalDate.now(), 21));
        return out;
    }

    private List<Map<String, Object>> workouts(int planId, int aid) {
        List<PlannedWorkout> rows = PlannedWorkout.forPlan(aid, planId);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (PlannedWorkout w : rows) {
            out.add(workoutDict(w));
        }
        return out;
    }

    /** _plan_dict: all Plan columns except weeks_json, plus weeks (parsed). */
    private Map<String, Object> planDict(Plan p) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", p.id);
        d.put("athlete_id", p.athleteId);
        d.put("race_name", p.raceName);
        d.put("race_date", p.raceDate);
        d.put("status", p.status);
        d.put("summary", p.summary);
        d.put("base_weekly_hours", p.baseWeeklyHours);
        d.put("created_at", p.createdAt);
        d.put("weeks", parseJson(p.weeksJson));
        return d;
    }

    /** _workout_dict: all PlannedWorkout columns except structure_json, plus
     * steps (parsed). Field set matches the Python model_dump exactly. */
    private Map<String, Object> workoutDict(PlannedWorkout w) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", w.id);
        d.put("athlete_id", w.athleteId);
        d.put("plan_id", w.planId);
        d.put("date", w.date);
        d.put("sport", w.sport);
        d.put("title", w.title);
        d.put("description", w.description);
        d.put("duration_s", w.durationS);
        d.put("distance_m", w.distanceM);
        d.put("planned_tss", w.plannedTss);
        d.put("intensity", w.intensity);
        d.put("fit_path", w.fitPath);
        d.put("zwo_path", w.zwoPath);
        d.put("status", w.status);
        d.put("matched_activity_id", w.matchedActivityId);
        d.put("created_at", w.createdAt);
        d.put("steps", parseJson(w.structureJson));
        return d;
    }

    /** Mirror Python `json.loads(x or "[]")`: a null/EMPTY column → []. A
     * non-empty value (incl. whitespace-only, which is truthy in Python) is
     * parsed and, like Python, THROWS on malformed input → 500 — so a corrupt
     * blob fails identically on both backends (the strangler serves reads with a
     * 5xx local fallback, so the client still gets FastAPI's answer). */
    private Object parseJson(String json) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        return PyJson.loads(json);
    }
}
