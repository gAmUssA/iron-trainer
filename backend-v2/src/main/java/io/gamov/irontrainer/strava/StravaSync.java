package io.gamov.irontrainer.strava;

import io.gamov.irontrainer.activity.Activity;
import io.gamov.irontrainer.athlete.Analysis;
import io.gamov.irontrainer.metrics.Metrics.Thresholds;
import io.gamov.irontrainer.metrics.MetricsWrite;
import io.gamov.irontrainer.util.Iso;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
 * summaries → map + upsert → prune old → de-dup (with device-name detail fetch)
 * → seed thresholds if empty → rebuild the PMC. BOTH external phases (activity
 * fetch, device-detail fetch) run OUTSIDE a DB transaction; the DB work is done
 * in QuarkusTransaction phases around them. */
@ApplicationScoped
public class StravaSync {

    private static final Logger LOG = Logger.getLogger(StravaSync.class);
    private static final int PER_PAGE = 200;
    // Cap device-detail lookups per run so a big backfill stays responsive and
    // survives rate limits (services.run_sync passes max_fetches=200).
    private static final int DEVICE_FETCH_CAP = 200;

    @Inject
    StravaTokens tokens;

    @Inject
    DedupService dedupService;

    @RestClient
    StravaApi strava;

    @ConfigProperty(name = "irontrainer.history-years", defaultValue = "5")
    int historyYears;

    public Map<String, Object> runSync(int aid, boolean full) {
        String token = tokens.validAccessToken(aid);         // tx: refresh if expired
        Long after = full ? historyCutoffEpoch() : latestActivityEpoch(aid);
        LOG.infof("Strava sync starting (athlete=%d, full=%s, after=%s).", aid, full, after);
        List<Map<String, Object>> raw = fetchAll(token, after);  // external, no tx

        // Phase 1 (tx): upsert + prune, then find clustered activities missing a
        // device name. Counts stashed for the final response.
        Upserted up = QuarkusTransaction.requiringNew().call(() -> {
            int upserted = upsert(aid, raw);
            int pruned = pruneOld(aid);
            List<Long> need = Dedup.clusteredNeedingDevice(
                    Activity.list("athleteId = ?1 order by startDate", aid));
            return new Upserted(upserted, pruned, need);
        });
        // Phase 2 (external, no tx): resolve missing device names from Strava.
        DedupService.DeviceFetch df = dedupService.resolveMissingDeviceNames(
                up.need(), "Bearer " + token, DEVICE_FETCH_CAP);
        // Phase 3 (tx): apply names → dedup → seed → rebuild → build the response.
        return QuarkusTransaction.requiringNew().call(() -> finalizeSync(aid, raw.size(), up, df));
    }

    private record Upserted(int upserted, int pruned, List<Long> need) {}

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
            throttle();   // be gentle with rate limits between pages (Python time.sleep(0.2))
        }
        return out;
    }

    /** Inter-page throttle mirroring strava.fetch_activities' time.sleep(0.2). */
    private static void throttle() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Phase 3 (tx): apply the fetched device names, mark duplicates (now
     * device-aware), seed thresholds if the profile is empty (re-costs
     * activities), then rebuild the PMC. Order matches FastAPI run_sync:
     * dedup → seed → rebuild. */
    Map<String, Object> finalizeSync(int aid, int fetchedCount, Upserted up, DedupService.DeviceFetch df) {
        List<Activity> acts = Activity.list("athleteId = ?1 order by startDate", aid);
        Dedup.applyDeviceNames(acts, df.devices());
        Dedup.Result d = Dedup.markDuplicates(acts);
        Map<String, Object> seeded = Analysis.seedProfileIfEmpty(aid, LocalDate.now());
        int metricsDays = MetricsWrite.rebuildMetrics(aid, acts);
        long deviceRemaining = d.clusters().stream().flatMap(List::stream)
                .filter(a -> a.deviceName == null || a.deviceName.isEmpty()).count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fetched", fetchedCount);
        out.put("upserted", up.upserted());
        out.put("pruned_old", up.pruned());
        out.put("total_activities", (int) Activity.count("athleteId", aid));
        out.put("duplicates_removed", d.duplicates());
        out.put("duplicate_clusters", d.clusters().size());
        // NOTE: run_sync's response omits device_fetched (only /dedup returns it).
        out.put("device_remaining", (int) deviceRemaining);
        out.put("metrics_days", metricsDays);
        out.put("profile_seeded", seeded != null);
        out.put("inferred_profile", seeded);
        LOG.infof("Strava sync done: fetched=%d upserted=%d pruned=%d dups=%d device_fetched=%d days=%d%s",
                fetchedCount, up.upserted(), up.pruned(), d.duplicates(), df.fetched(), metricsDays,
                seeded != null ? " (thresholds inferred)" : "");
        return out;
    }

    /** upsert_activities: map each raw activity, dedup the batch by id, then
     * insert-or-update by Strava id (device_name / is_duplicate are preserved on
     * update — _map_activity doesn't touch them). */
    private int upsert(int aid, List<Map<String, Object>> raw) {
        Thresholds th = MetricsWrite.thresholds(aid);
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();  // last-wins per id
        for (Map<String, Object> r : raw) {
            // Python filters on a truthy id (`if r.get("id")`), which also drops 0.
            if (!(r.get("id") instanceof Number id) || id.longValue() == 0) continue;
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
            applyMapped(a, m, isNew);
            if (isNew) a.persist();
        }
        return byId.size();
    }

    private static void applyMapped(Activity a, Map<String, Object> m, boolean isNew) {
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
        // created_at is the first-seen timestamp: set once at insert, never on
        // update (Python excludes it from _ACT_UPDATE_COLS).
        if (isNew) a.createdAt = (String) m.get("created_at");
        // device_name / is_duplicate / primary_id intentionally preserved on update.
    }

    /** delete_activities_before(cutoff): drop history older than the retention
     * window (no-op when history_years is 0 = keep all). */
    private int pruneOld(int aid) {
        if (historyYears <= 0) return 0;
        String cutoff = historyCutoffDate().toString();
        return (int) Activity.delete("athleteId = ?1 and startDate < ?2", aid, cutoff);
    }

    /** history_cutoff_date: today − round(365.25 × history_years) days, host-local
     * to match Python's date.today() (not UTC — mirrors the metrics-rebuild "today"). */
    private LocalDate historyCutoffDate() {
        return LocalDate.now().minusDays(Math.round(365.25 * historyYears));
    }

    /** history_cutoff_epoch: the cutoff date at UTC midnight, in unix seconds
     * (full-backfill lower bound; Python combines the local date with tz=utc). */
    private Long historyCutoffEpoch() {
        if (historyYears <= 0) return null;
        return historyCutoffDate().atStartOfDay().toEpochSecond(ZoneOffset.UTC);
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
