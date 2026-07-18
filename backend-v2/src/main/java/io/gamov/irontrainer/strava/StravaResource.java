package io.gamov.irontrainer.strava;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.metrics.MetricsWrite;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/** Strava vertical: de-duplication + activity sync. OAuth (connect/callback) and
 * the GDPR archive import are separate verticals (beans xtre/f6ui). */
@Path("/api/strava")
public class StravaResource {

    private static final Logger LOG = Logger.getLogger(StravaResource.class);

    @Inject
    CurrentAthlete current;

    @Inject
    StravaSync sync;

    @Inject
    StravaTokens tokens;

    @Inject
    DedupService dedupService;

    /** POST /api/strava/sync — pull activity summaries from Strava, upsert, prune
     * old, de-dup, and rebuild the PMC. Port of services.run_sync. 409 when Strava
     * isn't connected. NOT @Transactional: runSync keeps the external fetch out of
     * the DB transaction (persist has its own). */
    @POST
    @Path("/sync")
    public Map<String, Object> syncActivities(@QueryParam("full") @DefaultValue("false") boolean full) {
        int aid = current.require();
        return sync.runSync(aid, full);
    }

    /** POST /api/strava/dedup — re-run de-duplication on existing activities
     * (cluster same-event → keep one → rebuild the PMC). Port of the FastAPI
     * endpoint's deduplicate(fetch_details=...) + rebuild_metrics.
     *
     * fetch=true (default) looks up device names from Strava for clustered
     * activities that lack one, so primary selection is device-aware; that needs a
     * valid token (409 when not connected, refreshed on expiry via
     * validAccessToken). fetch=false is fully local. The detail fetches run
     * OUTSIDE the DB transaction; only the mark+rebuild is transactional. */
    @POST
    @Path("/dedup")
    public Map<String, Object> dedup(@QueryParam("fetch") @DefaultValue("true") boolean fetch,
                                     @QueryParam("limit") @DefaultValue("100") int limit) {
        int aid = current.require();
        // Connection guard + token refresh happen here (409 when not connected).
        String auth = fetch ? "Bearer " + tokens.validAccessToken(aid) : null;
        // Phase 1 (tx read): which clustered activities still need a device name.
        List<Long> need = fetch
                ? QuarkusTransaction.requiringNew().call(() ->
                        Dedup.clusteredNeedingDevice(loadActs(aid)))
                : List.of();
        // Phase 2 (external, no tx): fetch the missing device names.
        DedupService.DeviceFetch df = fetch
                ? dedupService.resolveMissingDeviceNames(need, auth, limit)
                : new DedupService.DeviceFetch(Map.of(), 0);
        // Phase 3 (tx): apply names → mark duplicates → rebuild the PMC.
        return QuarkusTransaction.requiringNew().call(() -> finalizeDedup(aid, df));
    }

    /** list_activities(include_duplicates=True) — ORDER BY start_date so the input
     * order (and same-start_date tie-breaks) matches FastAPI. */
    private static List<Activity> loadActs(int aid) {
        return Activity.list("athleteId = ?1 order by startDate", aid);
    }

    private Map<String, Object> finalizeDedup(int aid, DedupService.DeviceFetch df) {
        List<Activity> acts = loadActs(aid);
        Dedup.applyDeviceNames(acts, df.devices());
        Dedup.Result d = Dedup.markDuplicates(acts);
        int metricsDays = MetricsWrite.rebuildMetrics(aid, acts);  // reuse the loaded list
        // Python counts any falsy device_name (null OR empty string).
        long deviceRemaining = d.clusters().stream().flatMap(List::stream)
                .filter(a -> a.deviceName == null || a.deviceName.isEmpty()).count();
        LOG.infof("Dedup: athlete=%d clusters=%d duplicates=%d device_fetched=%d",
                aid, d.clusters().size(), d.duplicates(), df.fetched());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clusters", d.clusters().size());
        out.put("duplicates", d.duplicates());
        out.put("device_fetched", df.fetched());
        out.put("device_remaining", (int) deviceRemaining);
        out.put("metrics_days", metricsDays);
        return out;
    }
}
