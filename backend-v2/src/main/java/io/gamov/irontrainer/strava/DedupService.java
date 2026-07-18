package io.gamov.irontrainer.strava;

import io.gamov.irontrainer.activity.Activity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
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
        // Mirror services.deduplicate's `need[:max_fetches]` (max_fetches = limit or
        // None): null → all; positive → first N; negative → Python slice
        // need[:-k] = all but the last k (clamped ≥ 0). Never unbounded on misuse.
        int cap;
        if (maxFetches == null) {
            cap = needIds.size();
        } else if (maxFetches < 0) {
            cap = Math.max(0, needIds.size() + maxFetches);
        } else {
            cap = Math.min(maxFetches, needIds.size());
        }
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

    /** Persist fetched device names in their OWN transaction, right after the
     * fetch and BEFORE the mark/seed/rebuild finalize — so a finalize failure
     * doesn't discard names we already spent Strava quota on (the "names are
     * cached, resume next run" guarantee; mirrors repo.set_device_name committing
     * per fetch). Loads only the fetched activities. */
    @Transactional
    public void persistDeviceNames(Map<Long, String> devices) {
        if (devices.isEmpty()) return;
        for (Activity a : Activity.<Activity>list("id in ?1", devices.keySet())) {
            a.deviceName = devices.get(a.id);   // may be null — Strava reported none
        }
    }
}
