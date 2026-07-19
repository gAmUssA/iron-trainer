package io.gamov.irontrainer.plan;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.jobs.JobRunner;
import io.gamov.irontrainer.metrics.MetricDaily;
import io.gamov.irontrainer.nutrition.Nutrition;
import io.gamov.irontrainer.races.Races;
import io.gamov.irontrainer.readiness.DailyRecovery;
import io.gamov.irontrainer.readiness.Readiness;
import io.gamov.irontrainer.strava.StravaSync;
import io.gamov.irontrainer.tests.FitnessTests;
import io.gamov.irontrainer.tests.FitnessTestResult;
import io.gamov.irontrainer.util.Params;
import io.gamov.irontrainer.util.Py;
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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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

    @Inject
    StravaSync stravaSync;

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
        // Only an ABSENT week_start is 422 (FastAPI required-param). A present-empty
        // "?week_start=" is a valid string to FastAPI → flows through to the week
        // lookup → 400 "Week  not in active plan." (matched below).
        if (weekStart == null) {
            throw new WebApplicationException(422);
        }
        return replanOneWeek(aid, weekStart, Params.boolOr(useLlmParam, true), null);
    }

    /** service.replan_week body — shared by the endpoint (feel=null) and reconcile
     * (feel from the check-in, threaded into the LLM context). */
    private Map<String, Object> replanOneWeek(int aid, String weekStart, boolean useLlm,
                                              Map<String, Object> feel) {
        ReplanCtx ctx = QuarkusTransaction.requiringNew().call(() -> assembleReplan(aid, weekStart, useLlm, feel));

        List<Map<String, Object>> workouts = null;
        boolean llmUsed = false;
        if (useLlm) {
            try {
                // Throws Unavailable on no-key / failure / empty / malformed, so a
                // returned list is always non-empty → llm_used=true.
                workouts = llm.generateWeekWorkouts(
                        PyJson.dumps(ctx.week), PyJson.dumps(ctx.profile), PyJson.dumps(ctx.fitness));
                llmUsed = true;
            } catch (PlanLlm.Unavailable e) {
                workouts = null;
            }
        }
        if (workouts == null) {
            workouts = PlanTemplate.expandWeek(ctx.week, ctx.profile);
        }

        PlanValidator.WeekResult wr = PlanValidator.capWeekWorkouts(workouts);
        List<Map<String, Object>> fixed = wr.workouts();
        applyFueling(fixed, ctx.bodyWeightKg, ctx.gelCarbG, ctx.sweatRateLH);
        String weekEnd = LocalDate.parse(weekStart).plusDays(6).toString();
        int n = QuarkusTransaction.requiringNew().call(() ->
                PlannedWorkout.replaceWeek(aid, ctx.planId, weekStart, weekEnd, fixed));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("week_start", weekStart);
        resp.put("llm_used", llmUsed);
        resp.put("workouts", n);
        resp.put("notes", wr.notes());
        return resp;
    }

    /** POST /api/plan/reconcile — match completed activities to planned workouts,
     * then re-plan the next `weeks_ahead` (1-4) upcoming week(s). Port of
     * plan_router.reconcile + service.reconcile. No active plan → 400. */
    @POST
    @Path("/reconcile")
    public Map<String, Object> reconcile(@QueryParam("weeks_ahead") String weeksAheadParam,
                                         @QueryParam("use_llm") String useLlmParam) {
        int aid = current.require();
        int weeksAhead = weeksAheadParam == null ? 1 : Params.intParam(weeksAheadParam);
        if (weeksAhead < 1 || weeksAhead > 4) {
            throw new WebApplicationException(422);   // FastAPI Query(ge=1, le=4)
        }
        boolean useLlm = Params.boolOr(useLlmParam, true);
        Plan plan = QuarkusTransaction.requiringNew().call(() -> Plan.activeFor(aid));
        if (plan == null) {
            throw new BadRequestException("No active plan. Generate a plan first.");
        }
        return reconcileInternal(aid, weeksAhead, useLlm, LocalDate.now(), null);
    }

    /** service.reconcile body — shared by the endpoint (feel=null, today=now) and
     * the weekly check-in (feel from inputs, today=UTC). Assumes an active plan. */
    private Map<String, Object> reconcileInternal(int aid, int weeksAhead, boolean useLlm,
                                                  LocalDate today, Map<String, Object> feel) {
        Plan plan = QuarkusTransaction.requiringNew().call(() -> Plan.activeFor(aid));
        final int planId = plan.id;

        // 1) Match actuals to planned (writes statuses) — its own tx.
        Map<String, Object> matched = QuarkusTransaction.requiringNew()
                .call(() -> Reconcile.matchWorkouts(aid, planId, today));

        // 2) Re-plan the next weeks_ahead future weeks (LLM outside its own tx).
        String nextMonday = PlanTemplate.mondayOf(today).plusDays(7).toString();
        List<String> upcoming = upcomingWeekStarts(plan.weeksJson, nextMonday, weeksAhead);
        List<Map<String, Object>> replanned = new ArrayList<>();
        for (String ws : upcoming) {
            replanned.add(replanOneWeek(aid, ws, useLlm, feel));
        }

        // 3) Compliance summary + form flag — both reads, one transaction.
        Object[] cf = QuarkusTransaction.requiringNew().call(() -> {
            List<PlannedWorkout> wos = PlannedWorkout.forPlan(aid, planId);
            List<Activity> acts = Activity.list(
                    "athleteId = ?1 and (isDuplicate = 0 or isDuplicate is null) order by startDate", aid);
            Map<String, Object> comp = Compliance.recent(wos, acts, today, 21);
            MetricDaily last = MetricDaily.find("athleteId = ?1 order by date desc", aid).firstResult();
            return new Object[] {comp, formFlag(last == null ? null : last.tsb)};
        });

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("matched", matched);
        resp.put("compliance", cf[0]);
        resp.put("weeks_replanned", upcoming);
        resp.put("replanned", replanned);
        resp.put("form_flag", cf[1]);
        return resp;
    }

    /** POST /api/plan/checkin — the one-tap weekly loop: Strava sync (best-effort)
     * → match actuals → reconcile/replan next week → readiness + tests-due + key
     * sessions, with a narrative story. Port of plan_router.checkin +
     * service.weekly_checkin. ?async=1 runs it as a `checkin` job. */
    @POST
    @Path("/checkin")
    @jakarta.ws.rs.Consumes(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
    public Map<String, Object> checkin(Map<String, Object> body,
                                       @QueryParam("use_llm") String useLlmParam,
                                       @QueryParam("async") String asyncParam) {
        int aid = current.require();
        boolean useLlm = Params.boolOr(useLlmParam, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = (body != null && body.get("inputs") instanceof Map<?, ?> m)
                ? (Map<String, Object>) m : null;
        if (Params.boolOr(asyncParam, false)) {
            Map<String, Object> env = new LinkedHashMap<>();
            env.put("job", jobs.submit(aid, "checkin", () -> weeklyCheckin(aid, useLlm, inputs)));
            return env;
        }
        return weeklyCheckin(aid, useLlm, inputs);
    }

    private Map<String, Object> weeklyCheckin(int aid, boolean useLlm, Map<String, Object> inputs) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);   // readiness.today_utc()
        Map<String, Object> feel = Checkins.sanitizeFeel(inputs);
        List<String> story = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inputs", feel);

        // 1. Sync — best-effort; a Strava hiccup must not block the loop.
        try {
            Map<String, Object> sync = stravaSync.runSync(aid, false);
            long fetched = ((Number) sync.get("fetched")).longValue();
            Map<String, Object> synced = new LinkedHashMap<>();
            synced.put("fetched", sync.get("fetched"));
            synced.put("total", sync.get("total_activities"));
            out.put("synced", synced);
            story.add(fetched != 0
                    ? "Synced " + fetched + " new activit" + (fetched == 1 ? "y" : "ies") + " from Strava."
                    : "Strava sync: nothing new since last time.");
        } catch (WebApplicationException e) {
            out.put("synced", null);
            if (e.getResponse() != null && e.getResponse().getStatus() == 409) {
                story.add("Strava not connected — using local data.");
            } else {
                LOG.warnf(e, "Check-in sync failed");
                story.add("Strava sync failed — continuing with local data.");
            }
        } catch (RuntimeException e) {
            LOG.warnf(e, "Check-in sync failed");
            out.put("synced", null);
            story.add("Strava sync failed — continuing with local data.");
        }

        Plan plan = QuarkusTransaction.requiringNew().call(() -> Plan.activeFor(aid));
        if (plan == null) {
            out.put("status", "no_plan");
            story.add("No active plan — generate one on the Training Plan tab first.");
            out.put("story", story);
            return out;
        }
        final int planId = plan.id;

        String nextMonday = PlanTemplate.mondayOf(today).plusDays(7).toString();
        String nextSunday = PlanTemplate.mondayOf(today).plusDays(13).toString();
        double beforeH = weekHours(aid, planId, nextMonday, nextSunday);

        Map<String, Object> rec = reconcileInternal(aid, 1, useLlm, today, feel);
        double afterH = weekHours(aid, planId, nextMonday, nextSunday);
        Map<String, Object> reconcileOut = new LinkedHashMap<>();
        for (String k : List.of("matched", "compliance", "weeks_replanned", "form_flag")) {
            reconcileOut.put(k, rec.get(k));
        }
        out.put("reconcile", reconcileOut);
        Map<String, Object> nextWeek = new LinkedHashMap<>();
        nextWeek.put("week_start", nextMonday);
        nextWeek.put("hours_before", beforeH);
        nextWeek.put("hours_after", afterH);
        out.put("next_week", nextWeek);

        @SuppressWarnings("unchecked")
        Map<String, Object> comp = (Map<String, Object>) rec.get("compliance");
        if (comp.get("planned_sessions") instanceof Number ps && ps.intValue() != 0) {
            double cr = comp.get("completion_rate") == null ? 0.0
                    : ((Number) comp.get("completion_rate")).doubleValue();
            long pct = Py.roundInt(cr * 100);
            story.add("Last 3 weeks: " + comp.get("completed_sessions") + " of "
                    + comp.get("planned_sessions") + " sessions completed (" + pct + "%).");
        }
        String flag = (String) rec.get("form_flag");
        Double tsb = lastTsb(aid);
        if (!"unknown".equals(flag)) {
            story.add("Form: " + flag + (tsb != null ? " (TSB " + Py.f0signed(tsb) + ")." : "."));
        }

        Map<String, Object> ready = QuarkusTransaction.requiringNew().call(() -> computeReadiness(aid, today));
        out.put("readiness", ready);
        String readyLine = Readiness.storyLine(ready);
        if (readyLine != null) {
            story.add(readyLine);
        }
        String feelLine = Checkins.feelVsDataLine(feel, ready);
        if (feelLine != null) {
            story.add(feelLine);
        }
        if (!((List<?>) rec.get("weeks_replanned")).isEmpty()) {
            double delta = afterH - beforeH;
            if (Math.abs(delta) >= 0.2) {
                String verb = delta > 0 ? "stepped up" : "eased";
                story.add("Week of " + nextMonday + ": replanned — " + verb + " from "
                        + Py.f1(beforeH) + "h to " + Py.f1(afterH) + "h.");
            } else {
                story.add("Week of " + nextMonday + ": replanned at " + Py.f1(afterH) + "h (volume unchanged).");
            }
        }

        List<Map<String, Object>> due = QuarkusTransaction.requiringNew().call(() -> testsDue(aid, today));
        out.put("tests_due", due);
        if (!due.isEmpty()) {
            String names = due.stream().limit(3).map(d -> String.valueOf(d.get("name")))
                    .collect(Collectors.joining(", "));
            story.add("Fitness test" + (due.size() > 1 ? "s" : "") + " due: " + names + " (Tests tab).");
        }

        List<Map<String, Object>> key = QuarkusTransaction.requiringNew()
                .call(() -> keySessions(aid, planId, today.toString(), nextSunday));
        out.put("key_sessions", key);
        if (!key.isEmpty()) {
            String bits = key.stream().map(w -> w.get("title") + " ("
                    + ((w.get("duration_s") == null ? 0L : ((Number) w.get("duration_s")).longValue()) / 60)
                    + " min) on " + w.get("date")).collect(Collectors.joining("; "));
            story.add("Key sessions ahead: " + bits + ".");
        }

        out.put("status", "ok");
        out.put("story", story);
        // Persist — best-effort (a storage hiccup must not fail a check-in that replanned).
        try {
            QuarkusTransaction.requiringNew().run(() -> Checkin.save(aid, today.toString(), feel, story, ready));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Check-in persist failed");
        }
        return out;
    }

    /** Sum of a plan week's planned durations (h, 1dp) in [start, end]. */
    private double weekHours(int aid, int planId, String start, String end) {
        return QuarkusTransaction.requiringNew().call(() -> {
            long secs = 0;
            for (PlannedWorkout w : PlannedWorkout.forPlan(aid, planId)) {
                if (w.date != null && start.compareTo(w.date) <= 0 && w.date.compareTo(end) <= 0) {
                    secs += w.durationS == null ? 0 : w.durationS;
                }
            }
            return Py.round(secs / 3600.0, 1);
        });
    }

    /** (repo.get_metrics() or [{}])[-1].get("tsb") — last metric's TSB, or null. */
    private Double lastTsb(int aid) {
        return QuarkusTransaction.requiringNew().call(() -> {
            MetricDaily last = MetricDaily.find("athleteId = ?1 order by date desc", aid).firstResult();
            return last == null ? null : last.tsb;
        });
    }

    /** readiness.compute over the metrics series + recent recovery (35 days). */
    private Map<String, Object> computeReadiness(int aid, LocalDate today) {
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (MetricDaily m : MetricDaily.<MetricDaily>list("athleteId = ?1 order by date", aid)) {
            metrics.add(m.toRow());
        }
        List<Map<String, Object>> recovery = new ArrayList<>();
        for (DailyRecovery r : DailyRecovery.<DailyRecovery>find("athleteId = ?1 order by date desc", aid)
                .page(0, 35).list()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", r.date);
            row.put("sleep_h", r.sleepH);
            row.put("hrv_ms", r.hrvMs);
            row.put("rhr_bpm", r.rhrBpm);
            recovery.add(row);
        }
        return Readiness.compute(metrics, recovery, today);
    }

    /** last_tested_by_sport + catalog → the tests overdue for a retest. */
    private List<Map<String, Object>> testsDue(int aid, LocalDate today) {
        List<Object[]> rows = FitnessTestResult.getEntityManager()
                .createQuery("select r.sport, r.date from FitnessTestResult r where r.athleteId = ?1",
                        Object[].class)
                .setParameter(1, aid).getResultList();
        Map<String, String> lastBySport = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String sport = (String) row[0];
            String date = (String) row[1];
            if (sport != null && date != null && date.compareTo(lastBySport.getOrDefault(sport, "")) > 0) {
                lastBySport.put(sport, date);
            }
        }
        List<Map<String, Object>> due = new ArrayList<>();
        for (Map<String, Object> t : FitnessTests.catalog()) {
            String sport = (String) t.get("sport");
            String lastDate = lastBySport.get(sport);
            Long daysAgo = null;
            if (lastDate != null) {
                daysAgo = ChronoUnit.DAYS.between(LocalDate.parse(lastDate.substring(0, 10)), today);
            }
            if (lastDate == null || daysAgo >= FitnessTests.RETEST_DAYS) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("slug", t.get("slug"));
                d.put("name", t.get("name"));
                d.put("sport", sport);
                d.put("last_tested", lastDate);
                d.put("days_ago", daysAgo);
                due.add(d);
            }
        }
        return due;
    }

    /** The 2 highest-TSS sessions from [today, nextSunday] across the plan. */
    private List<Map<String, Object>> keySessions(int aid, int planId, String todayIso, String nextSunday) {
        List<PlannedWorkout> ahead = new ArrayList<>();
        for (PlannedWorkout w : PlannedWorkout.forPlan(aid, planId)) {
            if (w.date != null && todayIso.compareTo(w.date) <= 0 && w.date.compareTo(nextSunday) <= 0) {
                ahead.add(w);
            }
        }
        ahead.sort((x, y) -> Double.compare(
                y.plannedTss == null ? 0.0 : y.plannedTss, x.plannedTss == null ? 0.0 : x.plannedTss));
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlannedWorkout w : ahead.subList(0, Math.min(2, ahead.size()))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", w.date);
            m.put("sport", w.sport);
            m.put("title", w.title);
            m.put("duration_s", w.durationS);
            out.add(m);
        }
        return out;
    }

    /** plan weeks with week_start >= nextMonday, first `weeksAhead` (Python
     * [w for w in weeks if w.week_start >= next_monday][:weeks_ahead]). */
    @SuppressWarnings("unchecked")
    private List<String> upcomingWeekStarts(String weeksJson, String nextMonday, int weeksAhead) {
        Object parsed = (weeksJson == null || weeksJson.isBlank()) ? List.of() : PyJson.loads(weeksJson);
        List<String> matching = new ArrayList<>();
        for (Object o : (List<Object>) parsed) {
            if (o instanceof Map<?, ?> m) {
                Object ws = m.get("week_start");
                if (ws != null && String.valueOf(ws).compareTo(nextMonday) >= 0) {
                    matching.add(String.valueOf(ws));
                }
            }
        }
        // Python seq[:weeks_ahead] via the shared slice-bound helper.
        return new ArrayList<>(matching.subList(0, Params.sliceStop(weeksAhead, matching.size())));
    }

    /** Inputs replan_week reads: the active plan's week (by week_start), the
     * athlete profile (expand + fuel), and the fitness summary (LLM context). */
    @SuppressWarnings("unchecked")
    private ReplanCtx assembleReplan(int aid, String weekStart, boolean useLlm, Map<String, Object> feel) {
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
            if (feel != null && !feel.isEmpty()) {
                fitness.put("todays_feel", feel);   // service.replan_week: context["todays_feel"]=feel
            }
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
                // Python `(wo.get("description") or "").rstrip()`: a null/absent
                // description → "" (not the literal "null").
                Object desc = wo.get("description");
                String base = (desc == null ? "" : String.valueOf(desc)).stripTrailing();
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
