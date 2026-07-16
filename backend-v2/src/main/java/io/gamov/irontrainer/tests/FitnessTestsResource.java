package io.gamov.irontrainer.tests;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.util.Py;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/** Fitness-test reads — contract parity with FastAPI's /api/tests catalog,
 * results, and prefill. Bearer surface. Writes live in the write vertical. */
@Path("/api/tests")
public class FitnessTestsResource {

    private static final Logger LOG = Logger.getLogger(FitnessTestsResource.class);

    @Inject
    CurrentAthlete current;

    private List<FitnessTestResult> results(int aid) {
        return FitnessTestResult.list("athleteId = ?1 order by date", aid);
    }

    @GET
    public Map<String, Object> listTests() {
        int aid = current.require();
        // Most recent test date per sport (string max — ISO dates sort lexically).
        Map<String, String> lastBySport = new LinkedHashMap<>();
        for (FitnessTestResult r : results(aid)) {
            if (r.sport != null && r.date != null
                    && r.date.compareTo(lastBySport.getOrDefault(r.sport, "")) > 0) {
                lastBySport.put(r.sport, r.date);
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
                if (!truthy(a.distance) || !truthy(a.movingTime == null ? null : (double) a.movingTime)) continue;
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

    /** Python truthiness for a nullable number: present and non-zero. */
    private static boolean truthy(Double x) {
        return x != null && x != 0.0;
    }
}
