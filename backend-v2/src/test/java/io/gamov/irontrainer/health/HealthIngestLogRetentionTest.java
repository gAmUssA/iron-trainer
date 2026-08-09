package io.gamov.irontrainer.health;

import io.gamov.irontrainer.util.PyJson;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/** Retention prune (bean gce2): rows older than the window go; recent rows stay. */
@QuarkusTest
class HealthIngestLogRetentionTest {

    @Inject
    HealthIngestLogRetention retention;

    @Test
    void prunesOldRowsKeepsRecent() {
        String old = PyJson.utcIsoDaysAgo(200);
        String recent = PyJson.utcIsoDaysAgo(1);
        Long[] ids = QuarkusTransaction.requiringNew().call(() -> {
            HealthIngestLog a = row(old);
            HealthIngestLog b = row(recent);
            a.persist();
            b.persist();
            return new Long[] {a.id.longValue(), b.id.longValue()};
        });

        long deleted = retention.prune(90);
        assert deleted >= 1 : "should have pruned at least the 200-day-old row";

        boolean oldGone = QuarkusTransaction.requiringNew()
                .call(() -> HealthIngestLog.findById(ids[0].intValue()) == null);
        boolean recentKept = QuarkusTransaction.requiringNew()
                .call(() -> HealthIngestLog.findById(ids[1].intValue()) != null);
        assert oldGone : "the 200-day-old row should be pruned";
        assert recentKept : "the 1-day-old row should be kept";
    }

    static HealthIngestLog row(String receivedAt) {
        HealthIngestLog l = new HealthIngestLog();
        l.athleteId = 88001;
        l.source = "hae";
        l.ok = true;
        l.receivedAt = receivedAt;
        l.daysStored = 1;
        l.records = 1;
        l.unknownMetrics = 0;
        l.badDates = 0;
        l.byteSize = 100;
        return l;
    }
}
