package io.gamov.irontrainer.jobs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.function.Supplier;

/** Virtual-thread port of the FastAPI job system's shape: one (virtual)
 * thread per job, the job ROW is the source of truth, status transitions
 * committed in their own transactions so a crash strands nothing invisible.
 * Spike scope: no per-kind dedup or startup sweep yet — those come with the
 * jobs vertical (Phase 4-6). */
@ApplicationScoped
public class JobRunner {

    private static final Logger LOG = Logger.getLogger(JobRunner.class);

    public Integer submit(int athleteId, String kind, Supplier<String> work) {
        Integer jobId = QuarkusTransaction.requiringNew().call(() -> {
            Job j = new Job();
            j.athleteId = athleteId;
            j.kind = kind;
            j.status = "queued";
            j.createdAt = OffsetDateTime.now().toString();
            j.persist();
            return j.id;
        });
        LOG.infof("Job submitted: id=%d kind=%s athlete=%d", jobId, kind, athleteId);
        Thread.ofVirtual().name("job-" + jobId).start(() -> run(jobId, work));
        return jobId;
    }

    private void run(Integer jobId, Supplier<String> work) {
        long startNanos = System.nanoTime();
        transition(jobId, j -> {
            j.status = "running";
            j.startedAt = OffsetDateTime.now().toString();
        });
        LOG.debugf("Job running: id=%d", jobId);
        try {
            String result = work.get();
            transition(jobId, j -> {
                j.status = "succeeded";
                j.resultJson = result;
                j.finishedAt = OffsetDateTime.now().toString();
            });
            LOG.infof("Job succeeded: id=%d (%dms)", jobId, elapsedMs(startNanos));
        } catch (Exception e) {
            // toString() over getMessage(): never null (messageless exceptions
            // like NPE would persist a null error) and includes the class.
            String detail = e.toString();
            transition(jobId, j -> {
                j.status = "failed";
                j.error = detail;
                j.finishedAt = OffsetDateTime.now().toString();
            });
            // Load-bearing observability event — WARN WITH the throwable so the
            // stack trace reaches the logs; the exception is expected control
            // flow (job failed), not an app crash.
            LOG.warnf(e, "Job failed: id=%d (%dms): %s", jobId,
                    elapsedMs(startNanos), detail);
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
