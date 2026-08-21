package io.gamov.irontrainer.whoop;

import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.jobs.JobRunner;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
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

    @Inject
    JobRunner jobs;

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
                // Through JobRunner, NOT a direct call. concurrentExecution=SKIP only
                // stops two SCHEDULER firings overlapping; it does nothing about a
                // manual POST /api/whoop/sync running at the same time. Both paths
                // must share the per-athlete same-kind job block, or two syncs race
                // on upsert's find-then-write and one silently loses.
                jobs.submit(aid, "whoop_sync", () -> sync.runSync(aid, false).toRow());
            } catch (WebApplicationException e) {
                // The deliberate 409 from WhoopTokens: the athlete must reconnect.
                // A user action, not a fault — warn without a stack trace, and it
                // will recur daily until they do something about it.
                LOG.warnf("WHOOP daily sync skipped for athlete %d: %s", aid, e.getMessage());
            } catch (RuntimeException e) {
                // Everything else — rate limits, network, malformed payloads, DB
                // errors — is a genuine fault and gets a stack trace. Lumping these
                // in with "reconnect required" would hide a recurring outage behind
                // a message telling the user to click a button that will not help.
                LOG.errorf(e, "WHOOP daily sync FAILED for athlete %d", aid);
            }
        }
    }
}
