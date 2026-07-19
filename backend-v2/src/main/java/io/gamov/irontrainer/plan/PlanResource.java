package io.gamov.irontrainer.plan;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.jobs.JobRunner;
import io.gamov.irontrainer.metrics.MetricDaily;
import io.gamov.irontrainer.nutrition.Nutrition;
import io.gamov.irontrainer.races.Races;
import io.gamov.irontrainer.util.Params;
import io.gamov.irontrainer.util.PyJson;
import io.gamov.irontrainer.zones.HrZones;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/** Plan vertical — GET /api/plan (read), GET /api/plan/compliance, and
 * POST /api/plan/generate (season generation). Contract-parity with FastAPI
 * plan_router. checkin/replan/reconcile are later slices. */
@Path("/api/plan")
public class PlanResource {

    private static final Logger LOG = Logger.getLogger(PlanResource.class);

    /** Athlete fields the season builder + week expander read. */
    private static final List<String> PROFILE_KEYS = List.of(
            "ftp", "threshold_hr", "max_hr", "threshold_pace_run", "css_swim", "weekly_hours_target");

    @Inject
    CurrentAthlete current;

    @Inject
    Races races;

    @Inject
    PlanLlm llm;

    @Inject
    JobRunner jobs;

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

    /** POST /api/plan/generate — build the deterministic season skeleton,
     * optionally LLM-adapt it (falls back to the template when unavailable),
     * safety-validate, expand every week to workouts (+ fueling), and save.
     * ?async=1 runs it as a background job (kind generate_plan). Port of
     * plan_router.generate + service.generate_plan. */
    @POST
    @Path("/generate")
    public Map<String, Object> generate(@QueryParam("use_llm") String useLlmParam,
                                        @QueryParam("async") String asyncParam) {
        int aid = current.require();
        boolean useLlm = Params.boolOr(useLlmParam, true);   // FastAPI Query(True)
        if (Params.boolOr(asyncParam, false)) {
            Map<String, Object> env = new LinkedHashMap<>();
            env.put("job", jobs.submit(aid, "generate_plan", () -> generatePlan(aid, useLlm)));
            return env;
        }
        return generatePlan(aid, useLlm);
    }

    private Map<String, Object> generatePlan(int aid, boolean useLlm) {
        GenCtx ctx = QuarkusTransaction.requiringNew().call(() -> assembleGen(aid, useLlm));
        LocalDate today = LocalDate.now();                    // date.today()
        Map<String, Object> season = PlanTemplate.buildSeason(
                today, LocalDate.parse(ctx.raceDate), ctx.weeklyHours);
        season.put("race_name", ctx.raceName);
        season.put("base_weekly_hours", ctx.weeklyHours);

        boolean llmUsed = false;
        if (useLlm) {
            try {
                season = llm.adjustSeason(season, PyJson.dumps(ctx.profile),
                        PyJson.dumps(ctx.zones), PyJson.dumps(ctx.fitness));
                season.put("race_name", ctx.raceName);
                season.put("race_date", ctx.raceDate);
                season.put("base_weekly_hours", ctx.weeklyHours);
                llmUsed = true;
            } catch (PlanLlm.Unavailable e) {
                LOG.infof("Plan generate: LLM unavailable (%s) — deterministic template.", e.getMessage());
            }
        }

        PlanValidator.Result vr = PlanValidator.validateSeason(season);
        Map<String, Object> finalSeason = vr.season();
        final boolean llmU = llmUsed;
        return QuarkusTransaction.requiringNew().call(() -> {
            int planId = Plan.savePlan(aid, finalSeason);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> weeks = (List<Map<String, Object>>) finalSeason.get("weeks");
            List<Map<String, Object>> all = new ArrayList<>();
            for (Map<String, Object> wk : weeks) {
                List<Map<String, Object>> wos = PlanTemplate.expandWeek(wk, ctx.profile);
                wos = PlanValidator.capWeekWorkouts(wos).workouts();
                applyFueling(wos, ctx.bodyWeightKg(), ctx.gelCarbG(), ctx.sweatRateLH());
                all.addAll(wos);
            }
            int saved = PlannedWorkout.saveAll(aid, planId, all);
            LOG.infof("Plan %d created: %s, %d weeks, %d workouts, %d adjustment(s).",
                    planId, llmU ? "AI-adapted" : "template", weeks.size(), saved, vr.notes().size());
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("plan_id", planId);
            resp.put("llm_used", llmU);
            resp.put("weeks", weeks.size());
            resp.put("workouts", saved);
            resp.put("adjustments", vr.notes());
            resp.put("summary", finalSeason.get("summary"));
            return resp;
        });
    }

    /** POST /api/plan/replan-week — regenerate ONE week's workouts (LLM or the
     * template fallback), safety-cap, fuel, and replace that week's rows. Port of
     * plan_router.replan_week + service.replan_week. ValueError → 400. */
    @POST
    @Path("/replan-week")
    public Map<String, Object> replanWeek(@QueryParam("week_start") String weekStart,
                                          @QueryParam("use_llm") String useLlmParam) {
        int aid = current.require();
        if (weekStart == null || weekStart.isBlank()) {
            throw new WebApplicationException(422);   // required query param (FastAPI 422)
        }
        boolean useLlm = Params.boolOr(useLlmParam, true);
        ReplanCtx ctx = QuarkusTransaction.requiringNew().call(() -> assembleReplan(aid, weekStart, useLlm));

        List<Map<String, Object>> workouts = null;
        boolean llmUsed = false;
        if (useLlm) {
            try {
                workouts = llm.generateWeekWorkouts(
                        PyJson.dumps(ctx.week), PyJson.dumps(ctx.profile), PyJson.dumps(ctx.fitness));
                llmUsed = !workouts.isEmpty();
            } catch (PlanLlm.Unavailable e) {
                workouts = null;
            }
        }
        if (workouts == null || workouts.isEmpty()) {
            workouts = PlanTemplate.expandWeek(ctx.week, ctx.profile);
        }

        PlanValidator.WeekResult wr = PlanValidator.capWeekWorkouts(workouts);
        List<Map<String, Object>> fixed = wr.workouts();
        applyFueling(fixed, ctx.bodyWeightKg, ctx.gelCarbG, ctx.sweatRateLH);
        String weekEnd = LocalDate.parse(weekStart).plusDays(6).toString();
        final boolean llmU = llmUsed;
        int n = QuarkusTransaction.requiringNew().call(() ->
                PlannedWorkout.replaceWeek(aid, ctx.planId, weekStart, weekEnd, fixed));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("week_start", weekStart);
        resp.put("llm_used", llmU);
        resp.put("workouts", n);
        resp.put("notes", wr.notes());
        return resp;
    }

    /** Inputs replan_week reads: the active plan's week (by week_start), the
     * athlete profile (expand + fuel), and the fitness summary (LLM context). */
    @SuppressWarnings("unchecked")
    private ReplanCtx assembleReplan(int aid, String weekStart, boolean useLlm) {
        Athlete a = Athlete.findById(aid);
        Plan plan = Plan.activeFor(aid);
        if (plan == null) {
            throw new BadRequestException("No active plan. Generate a plan first.");
        }
        Object parsed = (plan.weeksJson == null || plan.weeksJson.isBlank())
                ? List.of() : PyJson.loads(plan.weeksJson);
        Map<String, Object> week = null;
        for (Object o : (List<Object>) parsed) {
            if (o instanceof Map<?, ?> m && weekStart.equals(m.get("week_start"))) {
                week = (Map<String, Object>) m;
                break;
            }
        }
        if (week == null) {
            throw new BadRequestException("Week " + weekStart + " not in active plan.");
        }
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("ftp", a == null ? null : a.ftp);
        profile.put("threshold_hr", a == null ? null : a.thresholdHr);
        profile.put("max_hr", a == null ? null : a.maxHr);
        profile.put("threshold_pace_run", a == null ? null : a.thresholdPaceRun);
        profile.put("css_swim", a == null ? null : a.cssSwim);

        Map<String, Object> fitness = null;
        if (useLlm) {
            MetricDaily last = MetricDaily.find("athleteId = ?1 order by date desc", aid).firstResult();
            Double tsb = last == null ? null : last.tsb;
            fitness = new LinkedHashMap<>();
            fitness.put("ctl", last == null ? null : last.ctl);
            fitness.put("atl", last == null ? null : last.atl);
            fitness.put("tsb", tsb);
            fitness.put("form_flag", formFlag(tsb));
        }
        return new ReplanCtx(plan.id, week, profile, fitness,
                a == null ? null : a.bodyWeightKg, a == null ? null : a.gelCarbG, a == null ? null : a.sweatRateLH);
    }

    private record ReplanCtx(int planId, Map<String, Object> week, Map<String, Object> profile,
                             Map<String, Object> fitness, Double bodyWeightKg, Double gelCarbG, Double sweatRateLH) {}

    /** _apply_fueling: append the one-line fueling note to each workout's
     * description (mutates in place). Mirrors service._apply_fueling. */
    private void applyFueling(List<Map<String, Object>> workouts,
                             Double bodyWeightKg, Double gelCarbG, Double sweatRateLH) {
        for (Map<String, Object> wo : workouts) {
            Integer durS = wo.get("duration_s") == null ? null : ((Number) wo.get("duration_s")).intValue();
            Map<String, Object> fueling = Nutrition.computeWorkoutFueling(
                    durS, (String) wo.get("intensity"), gelCarbG, bodyWeightKg, sweatRateLH);
            String note = Nutrition.fuelingNote(fueling);
            if (!note.isEmpty()) {
                String base = String.valueOf(wo.getOrDefault("description", "")).stripTrailing();
                if (!base.contains(note)) {
                    wo.put("description", (base + "\n" + note).strip());
                }
            }
        }
    }

    /** Inputs generate_plan reads: race, athlete profile (build/expand + fuel),
     * HR zones + fitness summary (LLM prompt context). */
    private GenCtx assembleGen(int aid, boolean useLlm) {
        Athlete a = Athlete.findById(aid);
        Map<String, Object> race = races.effectiveRace(a);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("ftp", a == null ? null : a.ftp);
        profile.put("threshold_hr", a == null ? null : a.thresholdHr);
        profile.put("max_hr", a == null ? null : a.maxHr);
        profile.put("threshold_pace_run", a == null ? null : a.thresholdPaceRun);
        profile.put("css_swim", a == null ? null : a.cssSwim);
        profile.put("weekly_hours_target", a == null ? null : a.weeklyHoursTarget);

        double weeklyHours = (a != null && a.weeklyHoursTarget != null && a.weeklyHoursTarget != 0.0)
                ? a.weeklyHoursTarget : 6.0;                 // weekly_hours_target or 6.0

        // HR zones + fitness summary are LLM-prompt context only — Python computes
        // them inside `if use_llm`, so skip the work (incl. the metrics read) for
        // the deterministic path.
        Map<String, Object> zones = null;
        Map<String, Object> fitness = null;
        if (useLlm) {
            zones = HrZones.hrZones(a == null ? null : a.thresholdHr, a == null ? null : a.maxHr);
            MetricDaily last = MetricDaily.find("athleteId = ?1 order by date desc", aid).firstResult();
            Double tsb = last == null ? null : last.tsb;
            fitness = new LinkedHashMap<>();
            fitness.put("ctl", last == null ? null : last.ctl);
            fitness.put("atl", last == null ? null : last.atl);
            fitness.put("tsb", tsb);
            fitness.put("form_flag", formFlag(tsb));
        }

        return new GenCtx((String) race.get("name"), (String) race.get("date"), weeklyHours,
                profile, a == null ? null : a.bodyWeightKg, a == null ? null : a.gelCarbG,
                a == null ? null : a.sweatRateLH, zones, fitness);
    }

    private static String formFlag(Double tsb) {
        if (tsb == null) {
            return "unknown";
        }
        if (tsb < -25) {
            return "fatigued";
        }
        if (tsb > 10) {
            return "fresh";
        }
        return "normal";
    }

    private record GenCtx(String raceName, String raceDate, double weeklyHours,
                          Map<String, Object> profile, Double bodyWeightKg, Double gelCarbG,
                          Double sweatRateLH, Map<String, Object> zones, Map<String, Object> fitness) {}

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
