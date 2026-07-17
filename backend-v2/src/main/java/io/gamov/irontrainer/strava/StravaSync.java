package io.gamov.irontrainer.strava;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.metrics.Metrics.Thresholds;
import io.gamov.irontrainer.metrics.MetricsWrite;
import io.gamov.irontrainer.util.Iso;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/** Strava sync — port of services.run_sync: refresh token → fetch activity
 * summaries → map + upsert → prune old → de-dup → rebuild the PMC. The external
 * fetch runs OUTSIDE a DB transaction; only the persist phase is transactional.
 * (Threshold inference — seed_profile_if_empty — is deferred to bean svinfer;
 * the device-name detail fetch is deferred to the dedup enrichment.) */
@ApplicationScoped
public class StravaSync {

    private static final Logger LOG = Logger.getLogger(StravaSync.class);
    private static final int PER_PAGE = 200;

    @Inject
    StravaTokens tokens;

    @RestClient
    StravaApi strava;

    @ConfigProperty(name = "irontrainer.history-years", defaultValue = "5")
    int historyYears;

    public Map<String, Object> runSync(int aid, boolean full) {
        String token = tokens.validAccessToken(aid);         // tx: refresh if expired
        Long after = full ? historyCutoffEpoch() : latestActivityEpoch(aid);
        LOG.infof("Strava sync starting (athlete=%d, full=%s, after=%s).", aid, full, after);
        List<Map<String, Object>> raw = fetchAll(token, after);  // external, no tx
        return persist(aid, raw);                            // tx: upsert + dedup + rebuild
    }

    /** Paginated fetch, mirroring strava.fetch_activities (per_page 200 until a
     * short page). Null `after` is omitted by the client (full history). */
    private List<Map<String, Object>> fetchAll(String accessToken, Long after) {
        String auth = "Bearer " + accessToken;
        List<Map<String, Object>> out = new ArrayList<>();
        int page = 1;
        while (true) {
            List<Map<String, Object>> batch = strava.activities(auth, PER_PAGE, page, after);
            if (batch == null || batch.isEmpty()) break;
            out.addAll(batch);
            if (batch.size() < PER_PAGE) break;
            page++;
        }
        return out;
    }

    @Transactional
    Map<String, Object> persist(int aid, List<Map<String, Object>> raw) {
        int upserted = upsert(aid, raw);
        int pruned = pruneOld(aid);
        // De-dup (device-name detail fetch deferred) then rebuild the PMC from
        // the mapping-time TSS. Ordered by start_date to match FastAPI.
        List<Activity> acts = Activity.list("athleteId = ?1 order by startDate", aid);
        Dedup.Result d = Dedup.markDuplicates(acts);
        int metricsDays = MetricsWrite.rebuildMetrics(aid, acts);
        long deviceRemaining = d.clusters().stream().flatMap(List::stream)
                .filter(a -> a.deviceName == null || a.deviceName.isEmpty()).count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fetched", raw.size());
        out.put("upserted", upserted);
        out.put("pruned_old", pruned);
        out.put("total_activities", (int) Activity.count("athleteId", aid));
        out.put("duplicates_removed", d.duplicates());
        out.put("duplicate_clusters", d.clusters().size());
        out.put("device_remaining", (int) deviceRemaining);
        out.put("metrics_days", metricsDays);
        out.put("profile_seeded", false);   // seed_profile_if_empty deferred (bean svinfer)
        out.put("inferred_profile", null);
        LOG.infof("Strava sync done: fetched=%d upserted=%d pruned=%d dups=%d days=%d",
                raw.size(), upserted, pruned, d.duplicates(), metricsDays);
        return out;
    }

    /** upsert_activities: map each raw activity, dedup the batch by id, then
     * insert-or-update by Strava id (device_name / is_duplicate are preserved on
     * update — _map_activity doesn't touch them). */
    private int upsert(int aid, List<Map<String, Object>> raw) {
        Thresholds th = MetricsWrite.thresholds(aid);
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();  // last-wins per id
        for (Map<String, Object> r : raw) {
            if (!(r.get("id") instanceof Number id)) continue;
            byId.put(id.longValue(), StravaMapping.mapActivity(r, th, aid));
        }
        for (Map<String, Object> m : byId.values()) {
            long id = ((Number) m.get("id")).longValue();
            Activity a = Activity.findById(id);
            boolean isNew = a == null;
            if (isNew) {
                a = new Activity();
                a.id = id;
                a.athleteId = aid;
            }
            applyMapped(a, m);
            if (isNew) a.persist();
        }
        return byId.size();
    }

    private static void applyMapped(Activity a, Map<String, Object> m) {
        a.sport = (String) m.get("sport");
        a.startDate = (String) m.get("start_date");
        a.name = (String) m.get("name");
        a.movingTime = asInt(m.get("moving_time"));
        a.elapsedTime = asInt(m.get("elapsed_time"));
        a.distance = asDouble(m.get("distance"));
        a.avgPower = asDouble(m.get("avg_power"));
        a.weightedPower = asDouble(m.get("weighted_power"));
        a.avgHr = asDouble(m.get("avg_hr"));
        a.maxHr = asDouble(m.get("max_hr"));
        a.avgSpeed = asDouble(m.get("avg_speed"));
        a.elevationGain = asDouble(m.get("elevation_gain"));
        a.hasPowerMeter = asInt(m.get("has_power_meter"));
        a.tss = asDouble(m.get("tss"));
        a.intensityFactor = asDouble(m.get("intensity_factor"));
        a.tssMethod = (String) m.get("tss_method");
        a.createdAt = (String) m.get("created_at");
        // device_name / is_duplicate / primary_id intentionally preserved on update.
    }

    /** delete_activities_before(cutoff): drop history older than the retention
     * window (no-op when history_years is 0 = keep all). */
    private int pruneOld(int aid) {
        if (historyYears <= 0) return 0;
        String cutoff = LocalDate.now(ZoneOffset.UTC).minusYears(historyYears).toString();
        return (int) Activity.delete("athleteId = ?1 and startDate < ?2", aid, cutoff);
    }

    /** now − history_years, in unix seconds (full-backfill lower bound). */
    private Long historyCutoffEpoch() {
        if (historyYears <= 0) return null;
        return LocalDate.now(ZoneOffset.UTC).minusYears(historyYears)
                .atStartOfDay().toEpochSecond(ZoneOffset.UTC);
    }

    /** Epoch of the athlete's most recent stored activity, or null (incremental
     * lower bound). */
    private Long latestActivityEpoch(int aid) {
        Activity latest = Activity.find("athleteId = ?1 order by startDate desc", aid).firstResult();
        if (latest == null || latest.startDate == null) return null;
        var dt = Iso.parseDateTime(latest.startDate);
        return dt == null ? null : dt.toEpochSecond(ZoneOffset.UTC);
    }

    private static Double asDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static Integer asInt(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }
}
