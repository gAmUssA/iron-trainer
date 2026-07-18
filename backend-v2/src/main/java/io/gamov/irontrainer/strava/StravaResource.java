package io.gamov.irontrainer.strava;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.jobs.JobRunner;
import io.gamov.irontrainer.metrics.MetricsWrite;
import io.gamov.irontrainer.util.Params;
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

    @Inject
    JobRunner jobs;

    /** POST /api/strava/sync — pull activity summaries from Strava, upsert, prune
     * old, de-dup, and rebuild the PMC. Port of services.run_sync. 409 when Strava
     * isn't connected. NOT @Transactional: runSync keeps the external fetch out of
     * the DB transaction (persist has its own).
     *
     * With ?async=1 the sync runs as a background job (kind "sync"); as in FastAPI
     * the connection check is NOT pre-run in async mode — a not-connected athlete
     * gets a failed job, not a 409 (only the 401 auth gate is synchronous). */
    @POST
    @Path("/sync")
    public Map<String, Object> syncActivities(@QueryParam("full") @DefaultValue("false") boolean full,
                                              @QueryParam("async") String asyncParam) {
        int aid = current.require();
        if (Params.boolOr(asyncParam, false)) {
            return env(jobs.submit(aid, "sync", () -> sync.runSync(aid, full)));
        }
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
     * OUTSIDE the DB transaction; only the mark+rebuild is transactional.
     *
     * With ?async=1 the fetch+mark+rebuild runs as a background job (kind
     * "dedup"); as in FastAPI the connection-guard 409 is acquired SYNCHRONOUSLY
     * (before the job) so a not-connected athlete still gets a 409, not a job. */
    @POST
    @Path("/dedup")
    public Map<String, Object> dedup(@QueryParam("fetch") @DefaultValue("true") boolean fetch,
                                     @QueryParam("limit") @DefaultValue("100") int limit,
                                     @QueryParam("async") String asyncParam) {
        int aid = current.require();
        // Connection guard + token refresh happen here (409 when not connected) —
        // synchronously even in async mode, matching FastAPI.
        String auth = fetch ? "Bearer " + tokens.validAccessToken(aid) : null;
        if (Params.boolOr(asyncParam, false)) {
            return env(jobs.submit(aid, "dedup", () -> runDedup(aid, auth, fetch, limit)));
        }
        return runDedup(aid, auth, fetch, limit);
    }

    /** The dedup phases (external fetch outside tx; mark+rebuild in tx). Runs
     * either inline or inside a background job; takes the pre-acquired auth token
     * so the connection-guard 409 stays synchronous. */
    private Map<String, Object> runDedup(int aid, String auth, boolean fetch, int limit) {
        // Phase 1 (tx read): which clustered activities still need a device name.
        List<Long> need = fetch
                ? QuarkusTransaction.requiringNew().call(() ->
                        Dedup.clusteredNeedingDevice(loadActs(aid)))
                : List.of();
        // Phase 2 (external, no tx): fetch the missing device names. limit=0 → all
        // (FastAPI `max_fetches = limit or None`).
        DedupService.DeviceFetch df = fetch
                ? dedupService.resolveMissingDeviceNames(need, auth, limit == 0 ? null : limit)
                : new DedupService.DeviceFetch(Map.of(), 0);
        // Phase 2.5 (tx): cache fetched names before the finalize can fail.
        dedupService.persistDeviceNames(df.devices());
        // Phase 3 (tx): mark duplicates (device-aware) → rebuild the PMC.
        return QuarkusTransaction.requiringNew().call(() -> finalizeDedup(aid, df.fetched()));
    }

    /** The async response envelope: {"job": <job dict>}. */
    private static Map<String, Object> env(Map<String, Object> job) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job", job);
        return out;
    }

    /** list_activities(include_duplicates=True) — ORDER BY start_date so the input
     * order (and same-start_date tie-breaks) matches FastAPI. */
    private static List<Activity> loadActs(int aid) {
        return Activity.list("athleteId = ?1 order by startDate", aid);
    }

    private Map<String, Object> finalizeDedup(int aid, int deviceFetched) {
        List<Activity> acts = loadActs(aid);   // device names already committed (phase 2.5)
        Dedup.Result d = Dedup.markDuplicates(acts);
        int metricsDays = MetricsWrite.rebuildMetrics(aid, acts);  // reuse the loaded list
        int deviceRemaining = Dedup.countDeviceless(d.clusters());
        LOG.infof("Dedup: athlete=%d clusters=%d duplicates=%d device_fetched=%d",
                aid, d.clusters().size(), d.duplicates(), deviceFetched);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clusters", d.clusters().size());
        out.put("duplicates", d.duplicates());
        out.put("device_fetched", deviceFetched);
        out.put("device_remaining", deviceRemaining);
        out.put("metrics_days", metricsDays);
        return out;
    }
}
