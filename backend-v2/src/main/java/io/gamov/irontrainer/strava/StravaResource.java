package io.gamov.irontrainer.strava;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.auth.SessionCookie;
import io.gamov.irontrainer.jobs.JobRunner;
import io.gamov.irontrainer.metrics.MetricsWrite;
import io.gamov.irontrainer.util.Params;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** Strava vertical: OAuth connect (bean xtre) + de-duplication + activity sync.
 * The OAuth callback/disconnect + GDPR import are separate slices (xtre/f6ui). */
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

    @Inject
    StravaOAuth oauth;

    @ConfigProperty(name = "irontrainer.session-secret")
    String sessionSecret;

    @ConfigProperty(name = "irontrainer.cookie-secure")
    boolean cookieSecure;

    /** GET /api/strava/connect — begin Strava OAuth (also the login entry point).
     * Mints a session cookie carrying the CSRF oauth_state and 307-redirects to
     * Strava's consent screen. Port of strava_router.connect. */
    @GET
    @Path("/connect")
    public Response connect() {
        if (!oauth.configured()) {
            throw new BadRequestException("Strava client ID/secret not configured in .env");
        }
        String state = StravaOAuth.newState();
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("oauth_state", state);
        NewCookie cookie = new NewCookie.Builder("session")
                .value(SessionCookie.sign(session, sessionSecret))
                .path("/")
                .maxAge((int) SessionCookie.MAX_AGE_SECONDS)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(NewCookie.SameSite.LAX)
                .build();
        LOG.debug("Strava OAuth connect: minted oauth_state, redirecting to Strava.");
        return Response.temporaryRedirect(URI.create(oauth.authorizeUrl(state))).cookie(cookie).build();
    }

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
    public Map<String, Object> syncActivities(@QueryParam("full") String fullParam,
                                              @QueryParam("async") String asyncParam) {
        int aid = current.require();
        // pydantic-lax bool parity: ?full=1 / ?full=yes → true (a plain JAX-RS
        // boolean coerces "1" to false).
        boolean full = Params.boolOr(fullParam, false);
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
    public Map<String, Object> dedup(@QueryParam("fetch") String fetchParam,
                                     @QueryParam("limit") @DefaultValue("100") int limit,
                                     @QueryParam("async") String asyncParam) {
        int aid = current.require();
        // pydantic-lax bool parity (default true): ?fetch=0 / ?fetch=no → false,
        // ?fetch=1 → true (a plain JAX-RS boolean coerces "1" to false).
        boolean fetch = Params.boolOr(fetchParam, true);
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
