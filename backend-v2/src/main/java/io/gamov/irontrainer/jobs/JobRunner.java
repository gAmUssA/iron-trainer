package io.gamov.irontrainer.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gamov.irontrainer.util.PyJson;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/** Virtual-thread port of the FastAPI job system (jobs.py + repo jobs): one
 * (virtual) thread per job, the job ROW is the source of truth, status
 * transitions committed in their own transactions so a crash strands nothing
 * invisible. Contract parity with the FastAPI side:
 *   - submit() dedups per (athlete, kind): a second submit while one is queued
 *     or running returns the EXISTING job with already_running=true instead of
 *     doing duplicate (rate-limited / paid) work.
 *   - submit() returns the job envelope dict (id/kind/status/…/result), not just
 *     an id, so the async endpoints can return {"job": <dict>}.
 *   - a startup sweep marks queued/running rows failed (their worker thread died
 *     with the previous process) so the UI never shows a forever-spinner. */
@ApplicationScoped
public class JobRunner {

    private static final Logger LOG = Logger.getLogger(JobRunner.class);

    /** Queued/running rows the sweep and dedup key off. */
    private static final List<String> ACTIVE = List.of("queued", "running");

    // Serializes the check-then-create in submit(): without it two simultaneous
    // submits of the same kind both see "no active job" and both start threads —
    // double Claude spend / double Strava calls. Process-wide is sufficient
    // (single instance; jobs are per-(athlete,kind) rows underneath). Mirrors
    // FastAPI's _submit_lock.
    private final Object submitLock = new Object();

    @Inject
    ObjectMapper mapper;

    /** Run {@code work} in a background virtual thread, tracked as a job row, and
     * return the job envelope dict. If a job of this (athlete, kind) is already
     * queued/running, return it with already_running=true and start nothing. The
     * work runs OUTSIDE request scope — it must take the athlete explicitly and
     * manage its own transactions (it does: sync/dedup/regen all do). */
    public Map<String, Object> submit(int athleteId, String kind, Supplier<Object> work) {
        Map<String, Object> dict;
        synchronized (submitLock) {
            Job existing = QuarkusTransaction.requiringNew()
                    .call(() -> activeJob(athleteId, kind));
            if (existing != null) {
                LOG.debugf("Job dedup: %s already active for athlete=%d (id=%d)",
                        kind, athleteId, existing.id);
                Map<String, Object> dedup = jobDict(existing);
                dedup.put("already_running", true);
                return dedup;
            }
            // Capture the queued-state envelope INSIDE the create tx, before the
            // worker starts — so the returned dict always reads status="queued"
            // like FastAPI's create_job, not a status that races the worker.
            dict = QuarkusTransaction.requiringNew().call(() -> {
                Job j = new Job();
                j.athleteId = athleteId;
                j.kind = kind;
                j.status = "queued";
                j.createdAt = PyJson.utcNowIso();
                j.persist();   // IDENTITY id assigned on flush
                return jobDict(j);
            });
        }
        Integer jobId = (Integer) dict.get("id");
        LOG.infof("Job submitted: id=%d kind=%s athlete=%d", jobId, kind, athleteId);
        Thread.ofVirtual().name("job-" + kind + "-" + jobId).start(() -> run(jobId, work));
        return dict;
    }

    /** The queued/running job of this (athlete, kind), newest first — the dedup
     * key. Mirrors repo.active_job. */
    private static Job activeJob(int athleteId, String kind) {
        return Job.find("athleteId = ?1 and kind = ?2 and status in ?3 order by id desc",
                athleteId, kind, ACTIVE).firstResult();
    }

    private void run(Integer jobId, Supplier<Object> work) {
        long startNanos = System.nanoTime();
        transition(jobId, j -> {
            j.status = "running";
            j.startedAt = PyJson.utcNowIso();
        });
        LOG.debugf("Job running: id=%d", jobId);
        try {
            Object result = work.get();
            // PyJson.dumps (not the plain mapper) for shared-DB byte parity: the
            // job table is read/written by BOTH backends, and result_json must be
            // byte-identical to FastAPI's json.dumps (", "/": " spacing).
            String resultJson = PyJson.dumps(result);
            transition(jobId, j -> {
                j.status = "succeeded";
                j.resultJson = resultJson;
                j.finishedAt = PyJson.utcNowIso();
            });
            LOG.infof("Job succeeded: id=%d (%dms)", jobId, elapsedMs(startNanos));
        } catch (Exception e) {
            // toString() over getMessage(): never null (messageless exceptions
            // like NPE would persist a null error) and includes the class.
            String detail = e.toString();
            transition(jobId, j -> {
                j.status = "failed";
                j.error = detail;
                j.finishedAt = PyJson.utcNowIso();
            });
            // WARN WITH the throwable so the stack trace reaches the logs; the
            // exception is expected control flow (job failed), not an app crash.
            LOG.warnf(e, "Job failed: id=%d (%dms): %s", jobId, elapsedMs(startNanos), detail);
        }
    }

    /** Startup hygiene: single-instance workers die with the process, so any
     * queued/running row after a boot is an orphan — mark it failed so the UI
     * never shows a forever-spinner. NOT athlete-scoped. Mirrors
     * repo.fail_stale_jobs / jobs.fail_stale_running. */
    void onStart(@Observes StartupEvent ev) {
        // Best-effort: startup hygiene must NEVER block boot. In prod the shared
        // schema already has the job table; but a fresh/empty DB (the native
        // smoke-run boots against one, and prod runs with Flyway OFF) would make
        // this query throw — swallow it and boot anyway rather than hard-crash
        // the healthcheck (bean backend-v2-railway-deploy).
        try {
            int n = failStaleJobs();
            if (n > 0) {
                LOG.warnf("Marked %d stale job(s) as failed (interrupted by restart).", n);
            }
        } catch (Exception e) {
            LOG.warnf(e, "Stale-job sweep skipped (job table unavailable at boot?).");
        }
    }

    int failStaleJobs() {
        return QuarkusTransaction.requiringNew().call(() ->
                (int) Job.update("status = 'failed', error = 'interrupted by restart', "
                                + "finishedAt = ?1 where status in ?2",
                        PyJson.utcNowIso(), ACTIVE));
    }

    /** The job envelope: every Job column except result_json, plus result (the
     * parsed result_json). Mirrors repo._job_dict. */
    public Map<String, Object> jobDict(Job j) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", j.id);
        d.put("athlete_id", j.athleteId);
        d.put("kind", j.kind);
        d.put("status", j.status);
        d.put("created_at", j.createdAt);
        d.put("started_at", j.startedAt);
        d.put("finished_at", j.finishedAt);
        d.put("result", parseResult(j.resultJson));
        d.put("error", j.error);
        return d;
    }

    private Object parseResult(String resultJson) {
        if (resultJson == null || resultJson.isEmpty()) {
            return null;
        }
        try {
            return mapper.readValue(resultJson, Object.class);
        } catch (Exception e) {
            // A stored result that won't parse is a bug, not a client error;
            // surface null rather than 500 the status poll.
            LOG.warnf(e, "Job result_json failed to parse; returning null result.");
            return null;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private void transition(Integer jobId, java.util.function.Consumer<Job> mutate) {
        QuarkusTransaction.requiringNew().run(() -> {
            Job j = Job.findById(jobId);
            if (j != null) {
                mutate.accept(j);
            }
        });
    }
}
