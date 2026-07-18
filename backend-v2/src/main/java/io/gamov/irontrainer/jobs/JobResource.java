package io.gamov.irontrainer.jobs;

import io.gamov.irontrainer.auth.CurrentAthlete;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Background-job status endpoints — contract parity with FastAPI
 * jobs_router: GET /api/jobs/{id} (athlete-scoped) and GET /api/jobs/summary
 * (latest terminal + active, per kind). */
@Path("/api/jobs")
public class JobResource {

    /** Terminal statuses for the "last called" summary. */
    private static final List<String> TERMINAL = List.of("succeeded", "failed");
    private static final List<String> ACTIVE = List.of("queued", "running");

    @Inject
    CurrentAthlete current;

    @Inject
    JobRunner jobs;

    /** GET /api/jobs/{id} — the job envelope. Athlete-scoped: a cross-tenant id
     * reads as missing (404), never leaks another athlete's job. Mirrors
     * repo.get_job. */
    @GET
    @Path("/{job_id}")
    public Map<String, Object> getJob(@PathParam("job_id") int jobId) {
        int aid = current.require();
        Job j = Job.findById(jobId);
        if (j == null || j.athleteId == null || j.athleteId != aid) {
            throw new NotFoundException("Job not found");
        }
        return jobs.jobDict(j);
    }

    /** GET /api/jobs/summary — latest terminal job per kind + currently active
     * jobs per kind. Mirrors jobs_router.summary. */
    @GET
    @Path("/summary")
    public Map<String, Object> summary() {
        int aid = current.require();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("latest", newestByKind(aid, TERMINAL, 200));
        out.put("active", newestByKind(aid, ACTIVE, 0));
        return out;
    }

    /** Newest job per kind for the given statuses (one query, newest-first, first
     * per kind wins). limit>0 caps the scan (matches repo.latest_jobs_by_kind's
     * limit 200); limit<=0 scans all (active set is tiny). */
    private Map<String, Object> newestByKind(int aid, List<String> statuses, int limit) {
        var query = Job.<Job>find(
                "athleteId = ?1 and status in ?2 order by id desc", aid, statuses);
        List<Job> rows = limit > 0 ? query.page(0, limit).list() : query.list();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Job j : rows) {
            out.putIfAbsent(j.kind, jobs.jobDict(j));
        }
        return out;
    }
}
