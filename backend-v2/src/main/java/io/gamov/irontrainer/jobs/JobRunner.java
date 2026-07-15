package io.gamov.irontrainer.jobs;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.OffsetDateTime;
import java.util.function.Supplier;

/** Virtual-thread port of the FastAPI job system's shape: one (virtual)
 * thread per job, the job ROW is the source of truth, status transitions
 * committed in their own transactions so a crash strands nothing invisible.
 * Spike scope: no per-kind dedup or startup sweep yet — those come with the
 * jobs vertical (Phase 4-6). */
@ApplicationScoped
public class JobRunner {

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
        Thread.ofVirtual().name("job-" + jobId).start(() -> run(jobId, work));
        return jobId;
    }

    private void run(Integer jobId, Supplier<String> work) {
        transition(jobId, j -> {
            j.status = "running";
            j.startedAt = OffsetDateTime.now().toString();
        });
        try {
            String result = work.get();
            transition(jobId, j -> {
                j.status = "succeeded";
                j.resultJson = result;
                j.finishedAt = OffsetDateTime.now().toString();
            });
        } catch (Exception e) {
            transition(jobId, j -> {
                j.status = "failed";
                j.error = e.getMessage();
                j.finishedAt = OffsetDateTime.now().toString();
            });
        }
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
