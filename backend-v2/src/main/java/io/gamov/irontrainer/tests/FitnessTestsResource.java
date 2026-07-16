package io.gamov.irontrainer.tests;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.plan.Plan;
import io.gamov.irontrainer.plan.PlannedWorkout;
import io.gamov.irontrainer.util.Py;
import io.gamov.irontrainer.util.PyJson;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/** Fitness-test vertical — contract parity with FastAPI's /api/tests: catalog,
 * results, prefill (reads) + record & schedule (writes). Bearer surface.
 * (apply — the cascade write — waits on the metrics-write vertical.) */
@Path("/api/tests")
public class FitnessTestsResource {

    private static final Logger LOG = Logger.getLogger(FitnessTestsResource.class);

    @Inject
    CurrentAthlete current;

    public static class RecordRequest {
        @JsonProperty("test_slug")
        public String testSlug;
        public String date;
        public Map<String, Object> inputs;
    }

    public static class ScheduleRequest {
        public String date;
    }

    // Results ordered by `date` only — deliberately the SAME clause as FastAPI's
    // list_test_results (no tie-break), so both backends read the identical order
    // from the shared DB. Full entities: listResults needs them for toRow().
    private List<FitnessTestResult> results(int aid) {
        return FitnessTestResult.list("athleteId = ?1 order by date", aid);
    }

    @GET
    public Map<String, Object> listTests() {
        int aid = current.require();
        // Most recent test date per sport (string max — ISO dates sort lexically).
        // Projection query: last_tested only needs (sport, date), not the JSON
        // blobs, so don't hydrate full result entities on this hot path.
        List<Object[]> rows = FitnessTestResult.getEntityManager()
                .createQuery("select r.sport, r.date from FitnessTestResult r "
                        + "where r.athleteId = ?1", Object[].class)
                .setParameter(1, aid).getResultList();
        Map<String, String> lastBySport = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String sport = (String) row[0];
            String date = (String) row[1];
            if (sport != null && date != null
                    && date.compareTo(lastBySport.getOrDefault(sport, "")) > 0) {
                lastBySport.put(sport, date);
            }
        }
        // "today" in UTC, matching FastAPI's _today() (datetime.now(timezone.utc)).
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> proto : FitnessTests.catalog()) {
            String lastDate = lastBySport.get((String) proto.get("sport"));
            boolean due = true;
            if (lastDate != null) {
                long age = ChronoUnit.DAYS.between(LocalDate.parse(lastDate), today);
                due = age >= FitnessTests.RETEST_DAYS;
            }
            Map<String, Object> row = new LinkedHashMap<>(proto);  // proto keys first
            row.put("last_tested", lastDate);
            row.put("due", due);
            out.add(row);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("tests", out);
        resp.put("retest_days", FitnessTests.RETEST_DAYS);
        LOG.debugf("Tests catalog: athlete=%d", aid);
        return resp;
    }

    @GET
    @Path("/results")
    public Map<String, Object> listResults() {
        int aid = current.require();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FitnessTestResult r : results(aid)) rows.add(r.toRow());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("results", rows);
        return resp;
    }

    @GET
    @Path("/{slug}/prefill")
    public Map<String, Object> prefill(@PathParam("slug") String slug,
                                       @QueryParam("limit") @DefaultValue("8") int limit) {
        int aid = current.require();
        Map<String, Object> proto = FitnessTests.get(slug);
        if (proto == null) throw new NotFoundException("Unknown test protocol");
        String sport = (String) proto.get("prefill_sport");
        List<Map<String, Object>> candidates = new ArrayList<>();
        Map<String, Object> resp = new LinkedHashMap<>();
        if (sport == null || sport.isEmpty()) {
            resp.put("candidates", candidates);
            return resp;
        }
        // list_activities() is oldest-first; scan most-recent-first (reversed).
        List<Activity> acts = Activity.list(
                "athleteId = ?1 and (isDuplicate = 0 or isDuplicate is null) order by startDate", aid);
        Collections.reverse(acts);
        for (Activity a : acts) {
            if (!sport.equals(a.sport)) continue;
            Map<String, Object> inputs = new LinkedHashMap<>();
            if ("bike-ftp-20".equals(slug)) {
                Double p = truthy(a.weightedPower) ? a.weightedPower : a.avgPower;
                if (!truthy(p)) continue;
                inputs.put("avg_power_w", Py.roundInt(p));
            } else if ("run-lthr-30".equals(slug)) {
                if (!truthy(a.distance) || !truthy(a.movingTime)) continue;
                inputs.put("distance_m", Py.roundInt(a.distance));
                inputs.put("time_s", Py.roundInt(a.movingTime));
                inputs.put("avg_hr_last20", truthy(a.avgHr) ? Py.roundInt(a.avgHr) : null);
            }
            if (!inputs.isEmpty()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("activity_id", a.id);
                c.put("date", a.startDate == null ? ""
                        : a.startDate.substring(0, Math.min(10, a.startDate.length())));
                c.put("name", a.name);
                c.put("inputs", inputs);
                candidates.add(c);
            }
            if (candidates.size() >= limit) break;
        }
        resp.put("candidates", candidates);
        return resp;
    }

    @POST
    @Path("/result")
    @Transactional
    public Map<String, Object> recordResult(RecordRequest body) {
        int aid = current.require();
        // FastAPI's RecordRequest requires test_slug + inputs (date is optional);
        // a missing required field / absent body is a 422 there, BEFORE the
        // handler runs — so validate before touching compute or the DB.
        if (body == null || body.testSlug == null || body.inputs == null) {
            throw new WebApplicationException("field required", 422);
        }
        Map<String, Object> proto = FitnessTests.get(body.testSlug);
        if (proto == null) throw new NotFoundException("Unknown test protocol");
        Map<String, Object> result;
        try {
            result = FitnessTests.compute(body.testSlug, body.inputs);
        } catch (FitnessTests.BadInput e) {
            throw new BadRequestException("Missing or invalid inputs: " + e.getMessage());
        }
        // Python: body.date or _today() (UTC).
        String date = (body.date != null && !body.date.isEmpty())
                ? body.date : LocalDate.now(ZoneOffset.UTC).toString();
        FitnessTestResult row = new FitnessTestResult();
        row.athleteId = aid;
        row.testSlug = body.testSlug;
        row.sport = (String) proto.getOrDefault("sport", "");
        row.date = date;
        row.inputsJson = PyJson.dumps(body.inputs);
        row.resultJson = PyJson.dumps(result);
        row.applied = false;
        row.createdAt = PyJson.utcNowIso();
        row.persist();
        LOG.infof("Fitness test recorded: athlete=%d slug=%s", aid, body.testSlug);
        return row.toRow();
    }

    @POST
    @Path("/{slug}/schedule")
    @Transactional
    public Map<String, Object> scheduleTest(@PathParam("slug") String slug, ScheduleRequest body) {
        int aid = current.require();
        // FastAPI's ScheduleRequest requires date → 422 before the handler; and
        // planned_workouts.date is NOT NULL, so persisting a null date would 500.
        if (body == null || body.date == null || body.date.isEmpty()) {
            throw new WebApplicationException("field required", 422);
        }
        if (FitnessTests.get(slug) == null) throw new NotFoundException("Unknown test protocol");
        Plan plan = Plan.activeFor(aid);
        if (plan == null) {
            throw new BadRequestException("No active plan — generate a plan before scheduling a test.");
        }
        Map<String, Object> workout = FitnessTests.toWorkout(slug);
        workout.put("date", body.date);
        // save_workouts(plan_id, [workout], replace_all=False): insert one row.
        PlannedWorkout pw = new PlannedWorkout();
        pw.athleteId = aid;
        pw.planId = plan.id;
        pw.date = body.date;
        pw.sport = (String) workout.get("sport");
        pw.title = (String) workout.get("title");
        pw.description = (String) workout.get("description");
        pw.structureJson = PyJson.dumps(workout.get("steps"));
        pw.durationS = (Integer) workout.get("duration_s");
        pw.intensity = (String) workout.get("intensity");
        pw.status = "planned";  // SQLModel default; reconcile skips non-"planned"
        pw.createdAt = PyJson.utcNowIso();
        pw.persist();
        LOG.infof("Test workout scheduled: athlete=%d slug=%s plan=%d", aid, slug, plan.id);
        return workout;
    }

    /** Python truthiness for a nullable number: present and non-zero. */
    private static boolean truthy(Double x) {
        return x != null && x != 0.0;
    }

    private static boolean truthy(Integer x) {
        return x != null && x != 0;
    }
}
