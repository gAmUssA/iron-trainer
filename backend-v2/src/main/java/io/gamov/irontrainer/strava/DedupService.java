package io.gamov.irontrainer.strava;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/** The EXTERNAL phase of device-name enrichment for de-duplication — Strava
 * detail fetches, run OUTSIDE any DB transaction (a per-activity HTTP call must
 * never hold a DB connection open). Callers do the DB read/cluster and the
 * mark/rebuild in their own transactions, passing the resolved names in.
 * Port of the fetch loop in services.deduplicate. */
@ApplicationScoped
public class DedupService {

    private static final Logger LOG = Logger.getLogger(DedupService.class);

    @RestClient
    StravaApi strava;

    /** Resolved device names (id → name; name may be null when Strava reports
     * none) plus the count of successful fetches. */
    public record DeviceFetch(Map<Long, String> devices, int fetched) {}

    /** Fetch device names for the given clustered, device-less activity ids,
     * capped at maxFetches. Breaks on 429 (rate limited — resumes next run since
     * names are cached); other failures skip that activity (best-effort). A
     * successful fetch counts even when Strava reports no device (the name stays
     * null → still "remaining"), matching services.deduplicate. */
    public DeviceFetch resolveMissingDeviceNames(List<Long> needIds, String auth, Integer maxFetches) {
        Map<Long, String> out = new LinkedHashMap<>();
        if (auth == null || needIds.isEmpty()) return new DeviceFetch(out, 0);
        int cap = (maxFetches != null && maxFetches > 0)
                ? Math.min(maxFetches, needIds.size()) : needIds.size();
        int fetched = 0;
        for (int i = 0; i < cap; i++) {
            long id = needIds.get(i);
            try {
                Map<String, Object> detail = strava.activityDetail(auth, id);
                Object dn = detail == null ? null : detail.get("device_name");
                out.put(id, dn == null ? null : dn.toString());
                fetched++;
            } catch (WebApplicationException e) {
                int status = e.getResponse() == null ? -1 : e.getResponse().getStatus();
                if (status == 429) break;                 // rate limited — resume next run
                LOG.debugf("device-detail fetch failed for activity %d (HTTP %d)", id, status);
            } catch (RuntimeException e) {
                LOG.debugf("device-detail fetch failed for activity %d: %s", id, e.toString());
            }
        }
        return new DeviceFetch(out, fetched);
    }
}
