package io.gamov.irontrainer.jobs;

import io.gamov.irontrainer.athlete.Athlete;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Virtual-thread job writing status transitions to the REAL shared job table —
 * the FastAPI job contract, ported: dict envelope, result serialization, and
 * per-(athlete,kind) dedup. */
@QuarkusTest
class JobRunnerTest {

    @Inject
    JobRunner runner;

    @Test
    void virtualThreadJobReachesSucceeded() {
        int athleteId = newAthlete("Job Probe");

        Map<String, Object> job = runner.submit(athleteId, "probe", () -> Map.of("ok", true));
        Integer jobId = (Integer) job.get("id");
        assertEquals("probe", job.get("kind"));
        assertEquals(athleteId, job.get("athlete_id"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            QuarkusTransaction.requiringNew().run(() -> {
                Job j = Job.findById(jobId);
                assertEquals("succeeded", j.status);
                // PyJson.dumps spacing (", "/": ") for shared-DB byte parity.
                assertEquals("{\"ok\": true}", j.resultJson);
            }));
    }

    @Test
    void secondSubmitOfActiveKindDedups() {
        int athleteId = newAthlete("Dedup Probe");

        // A job that blocks until released, so it stays "running" for the 2nd submit.
        Object lock = new Object();
        final boolean[] release = {false};
        Map<String, Object> first = runner.submit(athleteId, "slowkind", () -> {
            synchronized (lock) {
                while (!release[0]) {
                    try { lock.wait(50); } catch (InterruptedException ignored) { return Map.of(); }
                }
            }
            return Map.of("done", true);
        });
        Integer firstId = (Integer) first.get("id");

        // Wait until it's actually running, then a second submit must return the
        // SAME job with already_running=true and start nothing new.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            QuarkusTransaction.requiringNew().run(() -> {
                String st = statusOf(firstId);
                assertTrue("running".equals(st) || "queued".equals(st));
            }));

        Map<String, Object> second = runner.submit(athleteId, "slowkind", () -> Map.of("other", true));
        assertEquals(firstId, second.get("id"));
        assertEquals(Boolean.TRUE, second.get("already_running"));

        synchronized (lock) { release[0] = true; lock.notifyAll(); }
    }

    private static String statusOf(Integer jobId) {
        Job j = Job.findById(jobId);
        return j == null ? null : j.status;
    }

    private static int newAthlete(String name) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Athlete a = new Athlete();
            a.name = name;
            a.persist();
            return a.id;
        });
    }
}
