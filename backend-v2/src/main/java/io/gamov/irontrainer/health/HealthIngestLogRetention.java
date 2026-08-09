package io.gamov.irontrainer.health;

import io.gamov.irontrainer.util.PyJson;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

/**
 * Retention for the health-ingest audit log (bean gce2): the log grows a few rows
 * per day per athlete, so prune rows older than the window on boot. Boot-time
 * (mirrors JobRunner.onStart) rather than a scheduled timer — no scheduler
 * dependency, and Railway restarts on every deploy so it runs regularly enough for
 * an audit log. ponytail: boot-only prune; add @Scheduled if boots become rare.
 */
@ApplicationScoped
public class HealthIngestLogRetention {

    private static final Logger LOG = Logger.getLogger(HealthIngestLogRetention.class);
    private static final int RETENTION_DAYS = 90;

    void onStart(@Observes StartupEvent ev) {
        // Best-effort: never block boot (a fresh/empty DB or a missing table must not
        // crash the healthcheck — same stance as JobRunner's stale-job sweep).
        try {
            long deleted = prune(RETENTION_DAYS);
            if (deleted > 0) {
                LOG.infof("Pruned %d health_ingest_log row(s) older than %d days.", deleted, RETENTION_DAYS);
            }
        } catch (Exception e) {
            LOG.warnf(e, "health_ingest_log prune skipped (table unavailable at boot?).");
        }
    }

    /** Delete audit rows older than {@code days}, but ALWAYS keep the newest row per
     * (athlete, source) — otherwise a client quiet for >{@code days} loses its only
     * row and vanishes from stale-detection (last_by_source), the opposite of what
     * we want. received_at is ISO-8601-UTC (sorts lexicographically = chronologically). */
    long prune(int days) {
        String cutoff = PyJson.utcIsoDaysAgo(days);
        return QuarkusTransaction.requiringNew().call(() -> (long) HealthIngestLog.getEntityManager()
                .createQuery("delete from HealthIngestLog l where l.receivedAt < ?1 and l.id not in "
                        + "(select max(l2.id) from HealthIngestLog l2 group by l2.athleteId, l2.source)")
                .setParameter(1, cutoff)
                .executeUpdate());
    }
}
