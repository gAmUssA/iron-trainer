package io.gamov.irontrainer.whoop;

import io.gamov.irontrainer.athlete.Athlete;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

/** Daily WHOOP catch-up (bean 4a6s).
 *
 * <p>10:00 local, once a day. WHOOP scores recovery once each morning, so a single
 * run costs about four requests against a 10,000/day budget and catches the whole
 * day's data. That is also the argument for not building webhooks: they would add
 * a public HTTPS endpoint, HMAC verification, a delivery-dedup store and a
 * reconciliation poll anyway — for a metric that changes once, overnight.
 *
 * <p>Set {@code WHOOP_SYNC_CRON=off} to disable, which is the sensible default for
 * a self-hoster who only ever uploads the export ZIP.
 */
@ApplicationScoped
public class WhoopSyncScheduler {

    private static final Logger LOG = Logger.getLogger(WhoopSyncScheduler.class);

    @Inject
    WhoopSync sync;

    @Inject
    WhoopTokens tokens;

    @Scheduled(cron = "{whoop.sync-cron}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void daily() {
        if (!tokens.configured()) {
            return;   // no WHOOP credentials on this deployment — nothing to do
        }
        // Only athletes who actually connected. A self-host install with one
        // unconnected athlete does zero HTTP.
        List<Integer> connected = QuarkusTransaction.requiringNew().call(() ->
                Athlete.<Athlete>find("whoopRefreshToken is not null and whoopRefreshToken <> ''")
                        .list().stream().map(a -> a.id).toList());
        if (connected.isEmpty()) {
            return;
        }
        LOG.infof("WHOOP daily sync starting for %d athlete(s).", connected.size());
        for (Integer aid : connected) {
            try {
                WhoopSync.Result r = sync.runSync(aid, false);
                LOG.infof("WHOOP daily sync: athlete=%d cycles=%d written=%d",
                        aid, r.cycles(), r.written());
            } catch (RuntimeException e) {
                // One athlete's expired token must not stop everyone else's sync.
                // A 409 here means "reconnect required" and will recur daily until
                // they do — logged at warn, not error, because it is a user action
                // rather than a fault.
                LOG.warnf("WHOOP daily sync failed for athlete %d: %s", aid, e.getMessage());
            }
        }
    }
}
