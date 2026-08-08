package io.gamov.irontrainer.admin;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.jobs.Job;
import io.gamov.irontrainer.readiness.DailyRecovery;
import io.quarkus.panache.common.Sort;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin users view (bean y8b2). Cross-athlete user list + per-user detail behind the
 * @RequireAdmin gate. NEVER exposes Strava tokens (strava_access/refresh_token) —
 * only a derived `connected` flag. Small user base, so per-user count queries (N+1)
 * are fine; aggregate with group-by if the roster ever grows.
 */
@Path("/api/admin")
public class AdminUsersResource {

    @GET
    @Path("/users")
    @Produces(MediaType.APPLICATION_JSON)
    @RequireAdmin
    public Map<String, Object> users() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Athlete a : Athlete.<Athlete>listAll(Sort.by("id"))) {
            Map<String, Object> m = summary(a);
            m.put("activities", Activity.count("athleteId", a.id));
            m.put("jobs", Job.count("athleteId", a.id));
            m.put("failed_jobs", Job.count("athleteId = ?1 and status = ?2", a.id, "failed"));
            out.add(m);
        }
        return Map.of("users", out);
    }

    @GET
    @Path("/users/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequireAdmin
    public Map<String, Object> user(@PathParam("id") int id) {
        Athlete a = Athlete.findById(id);
        if (a == null) {
            throw new WebApplicationException("User not found.", 404);
        }
        Map<String, Object> m = summary(a);
        m.put("ftp", a.ftp);
        m.put("threshold_hr", a.thresholdHr);
        m.put("max_hr", a.maxHr);
        m.put("weekly_hours_target", a.weeklyHoursTarget);
        m.put("updated_at", a.updatedAt);

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("activities", Activity.count("athleteId", id));
        counts.put("recovery_days", DailyRecovery.count("athleteId", id));
        counts.put("jobs", Job.count("athleteId", id));
        m.put("counts", counts);

        // One query, newest-first: derive both "last job per kind" and "recent jobs".
        List<Job> jobs = Job.find("athleteId = ?1 order by id desc", id).page(0, 200).list();
        Map<String, Object> lastByKind = new LinkedHashMap<>();
        List<Map<String, Object>> recent = new ArrayList<>();
        for (Job j : jobs) {
            lastByKind.computeIfAbsent(j.kind, k -> jobRow(j));
            if (recent.size() < 10) {
                recent.add(jobRow(j));
            }
        }
        m.put("last_sync", lastByKind);
        m.put("recent_jobs", recent);
        return m;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Public-safe athlete summary — id/name/strava-id + derived connection flags.
     * Deliberately excludes strava_access_token / strava_refresh_token. */
    private static Map<String, Object> summary(Athlete a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id);
        m.put("name", a.name);
        m.put("strava_athlete_id", a.stravaAthleteId);
        m.put("connected", a.stravaRefreshToken != null && !a.stravaRefreshToken.isEmpty());
        m.put("apple_linked", a.appleUserId != null && !a.appleUserId.isEmpty());
        return m;
    }

    private static Map<String, Object> jobRow(Job j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.id);
        m.put("kind", j.kind);
        m.put("status", j.status);
        m.put("created_at", j.createdAt);
        m.put("finished_at", j.finishedAt);
        m.put("error", j.error == null || j.error.length() <= 200 ? j.error : j.error.substring(0, 200) + "…");
        return m;
    }
}
