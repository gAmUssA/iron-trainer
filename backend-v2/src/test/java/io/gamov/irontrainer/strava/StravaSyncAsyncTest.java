package io.gamov.irontrainer.strava;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Regression for the async sync failure: runSync's setup reads the athlete's
 * latest activity on the JobRunner virtual thread, where there is no request
 * context — the read must open its own transaction or it throws
 * ContextNotActiveException (every incremental async sync failed on it). */
@QuarkusTest
class StravaSyncAsyncTest {

    @Inject
    StravaSync sync;

    @Test
    void latestActivityEpochWorksOffTheRequestThread() throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        // A virtual thread mirrors JobRunner: no active CDI request context.
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                sync.latestActivityEpoch(999_999); // no such athlete → null, but the query still runs
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        t.join();
        assert thrown.get() == null : "sync setup read threw off the request thread: " + thrown.get();
    }
}
