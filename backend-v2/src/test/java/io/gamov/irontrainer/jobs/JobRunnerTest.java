package io.gamov.irontrainer.jobs;

import io.gamov.irontrainer.athlete.Athlete;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Spike item: virtual-thread job writing status transitions to the REAL
 * shared job table — the FastAPI job contract, ported. */
@QuarkusTest
class JobRunnerTest {

    @Inject
    JobRunner runner;

    @Test
    void virtualThreadJobReachesSucceeded() {
        int athleteId = QuarkusTransaction.requiringNew().call(() -> {
            Athlete a = new Athlete();
            a.name = "Job Probe";
            a.persist();
            return a.id;
        });

        Integer jobId = runner.submit(athleteId, "probe", () -> "{\"ok\":true}");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            QuarkusTransaction.requiringNew().run(() -> {
                Job j = Job.findById(jobId);
                assertEquals("succeeded", j.status);
                assertEquals("{\"ok\":true}", j.resultJson);
            }));
    }
}
