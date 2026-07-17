package io.gamov.irontrainer.strava;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.metrics.MetricsWrite;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/** Strava vertical (deterministic core): activity de-duplication. The Strava API
 * client, OAuth, and full sync are separate verticals (beans wc60/xtre/f6ui). */
@Path("/api/strava")
public class StravaResource {

    private static final Logger LOG = Logger.getLogger(StravaResource.class);

    @Inject
    CurrentAthlete current;

    /** POST /api/strava/dedup — re-run de-duplication on existing activities
     * (cluster same-event → keep one → rebuild the PMC). Port of the FastAPI
     * endpoint's deduplicate(fetch_details=...) + rebuild_metrics.
     *
     * fetch=true (default) asks FastAPI to look up device names from Strava; that
     * needs the API client (deferred), so here it only enforces the same
     * connection guard (409 when not connected) and otherwise dedups on the
     * device names already stored. fetch=false is fully local + parity-tested. */
    @POST
    @Path("/dedup")
    @Transactional
    public Map<String, Object> dedup(@QueryParam("fetch") @DefaultValue("true") boolean fetch,
                                     @QueryParam("limit") @DefaultValue("100") int limit) {
        int aid = current.require();
        if (fetch) {
            // Same connection precondition as FastAPI's valid_access_token
            // (`not refresh` = null OR empty). NOTE: the live device-name lookup
            // (and token-expiry refresh) needs the Strava API client — deferred to
            // bean wc60; a connected athlete here dedups on already-stored device
            // names only, so device_fetched is always 0 until then.
            Athlete a = Athlete.findById(aid);
            if (a == null || a.stravaRefreshToken == null || a.stravaRefreshToken.isEmpty()) {
                throw new WebApplicationException("Not connected to Strava", 409);
            }
        }
        // list_activities(include_duplicates=True) — ORDER BY start_date so the
        // input order (and thus same-start_date tie-breaks) matches FastAPI.
        List<Activity> acts = Activity.list("athleteId = ?1 order by startDate", aid);
        for (Activity a : acts) {  // clear_duplicate_flags (managed → flushes)
            a.isDuplicate = 0;
            a.primaryId = null;
        }
        List<List<Activity>> clusters = Dedup.clusterDuplicates(acts);
        int duplicates = 0;
        for (List<Activity> cluster : clusters) {
            Activity primary = Dedup.primaryOf(cluster);
            for (Activity a : cluster) {
                boolean isDup = !a.id.equals(primary.id);
                a.isDuplicate = isDup ? 1 : 0;
                a.primaryId = primary.id;
                if (isDup) duplicates++;
            }
        }
        int metricsDays = MetricsWrite.rebuildMetrics(aid, acts);  // reuse the loaded list
        // Python counts any falsy device_name (null OR empty string).
        long deviceRemaining = clusters.stream().flatMap(List::stream)
                .filter(a -> a.deviceName == null || a.deviceName.isEmpty()).count();
        LOG.infof("Dedup: athlete=%d clusters=%d duplicates=%d", aid, clusters.size(), duplicates);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clusters", clusters.size());
        out.put("duplicates", duplicates);
        out.put("device_fetched", 0);
        out.put("device_remaining", (int) deviceRemaining);
        out.put("metrics_days", metricsDays);
        return out;
    }
}
