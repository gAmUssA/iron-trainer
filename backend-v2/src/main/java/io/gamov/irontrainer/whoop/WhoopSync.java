package io.gamov.irontrainer.whoop;

import io.gamov.irontrainer.util.PyJson;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/** WHOOP API → whoop_cycles (bean 4a6s).
 *
 * <p>Two modes, mirroring {@code StravaSync.runSync(aid, full)}:
 * <ul>
 *   <li><b>full</b> — walk back {@code history-years}. Idempotent, so re-running is
 *       free and self-healing. <b>This is the gap detection.</b> A five-year walk is
 *       ~230 requests against a 10,000/day budget, which is cheap enough that
 *       building explicit gap-run detection would be more code than it saves.</li>
 *   <li><b>incremental</b> — the scheduled 10:00 run: a short window back from today.
 *       The window exists because WHOOP re-scores recovery and sleep after the fact
 *       and there is <b>no {@code updated_since} filter anywhere in v2</b>, so the
 *       only way to notice a re-score is to look again.</li>
 * </ul>
 *
 * <p>All HTTP happens outside transactions; the database work is a separate phase
 * afterwards. Same discipline as StravaSync — holding a transaction open across a
 * paginated remote call is how you get connection-pool exhaustion under load.
 */
@ApplicationScoped
public class WhoopSync {

    private static final Logger LOG = Logger.getLogger(WhoopSync.class);

    /** WHOOP caps page size at 25; asking for more is rejected, not clamped. */
    private static final int PAGE = 25;

    /** Hard stop on pagination. A cursor bug that never returns null would
     * otherwise spin until the daily rate limit is gone. 500 pages x 25 = 12,500
     * cycles ≈ 34 years, far past any real history. */
    private static final int MAX_PAGES = 500;

    /** How far back the incremental run looks.
     *
     * <p>GUESS, not a documented value: WHOOP publishes no statement about how long
     * after the fact a cycle can be re-scored. Three days is deliberately generous
     * for a 3-request-per-page walk. If re-scores are ever seen landing outside it,
     * widen this rather than adding change-detection machinery. */
    private static final int INCREMENTAL_LOOKBACK_DAYS = 3;

    @RestClient
    WhoopApi whoop;

    @Inject
    WhoopTokens tokens;

    @ConfigProperty(name = "irontrainer.history-years", defaultValue = "5")
    int historyYears;

    /** What one sync did, for the job envelope and the UI. */
    public record Result(int cycles, int written, int skipped, String from, String to) {
        public Map<String, Object> toRow() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cycles", cycles);
            m.put("written", written);
            m.put("skipped", skipped);
            m.put("from", from);
            m.put("to", to);
            return m;
        }
    }

    /** Full backfill or incremental catch-up. */
    public Result runSync(int aid, boolean full) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = full
                ? today.minusYears(historyYears)
                : today.minusDays(INCREMENTAL_LOOKBACK_DAYS);
        return sync(aid, from.atStartOfDay().toInstant(ZoneOffset.UTC).toString(), null);
    }

    /** Fetch a window and upsert it. `end` null means "up to now". */
    public Result sync(int aid, String startIso, String endIso) {
        // Token resolution and every HTTP call happen OUTSIDE any transaction.
        String auth = "Bearer " + tokens.validAccessToken(aid);

        List<Map<String, Object>> cycles = page(t -> whoop.cycles(auth, startIso, endIso, PAGE, t));
        List<Map<String, Object>> recovery = page(t -> whoop.recovery(auth, startIso, endIso, PAGE, t));
        List<Map<String, Object>> sleeps = page(t -> whoop.sleep(auth, startIso, endIso, PAGE, t));

        Map<Long, Map<String, Object>> recoveryByCycle = indexBy(recovery, "cycle_id");
        // Naps excluded: the join key is the WAKE date of the night's sleep, and a
        // Tuesday afternoon nap would otherwise overwrite Tuesday's real wake time.
        Map<Long, Map<String, Object>> sleepByCycle = new LinkedHashMap<>();
        for (Map<String, Object> s : sleeps) {
            if (Boolean.TRUE.equals(s.get("nap"))) {
                continue;
            }
            Long cid = asLong(s.get("cycle_id"));
            if (cid != null) {
                sleepByCycle.putIfAbsent(cid, s);
            }
        }

        List<WhoopCycle> rows = new ArrayList<>();
        for (Map<String, Object> cycle : cycles) {
            WhoopCycle row = toRow(cycle, recoveryByCycle, sleepByCycle);
            if (row != null) {
                rows.add(row);
            }
        }

        int[] counts = QuarkusTransaction.requiringNew().call(() -> upsert(aid, rows));
        LOG.infof("WHOOP sync: athlete=%d cycles=%d written=%d skipped=%d from=%s",
                aid, cycles.size(), counts[0], counts[1], startIso);
        return new Result(cycles.size(), counts[0], counts[1], startIso, endIso);
    }

    // ── read ─────────────────────────────────────────────────────────────────

    private interface Pager {
        Map<String, Object> get(String nextToken);
    }

    /** Walk a cursor-paginated collection to the end. */
    private static List<Map<String, Object>> page(Pager pager) {
        List<Map<String, Object>> all = new ArrayList<>();
        String token = null;
        for (int i = 0; i < MAX_PAGES; i++) {
            Map<String, Object> resp = pager.get(token);
            all.addAll(WhoopApi.records(resp));
            token = WhoopApi.nextToken(resp);
            if (token == null) {
                return all;
            }
        }
        LOG.warnf("WHOOP pagination hit the %d-page cap — results may be truncated.", MAX_PAGES);
        return all;
    }

    private static Map<Long, Map<String, Object>> indexBy(List<Map<String, Object>> rows, String key) {
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Long id = asLong(r.get(key));
            if (id != null) {
                out.putIfAbsent(id, r);
            }
        }
        return out;
    }

    // ── map ──────────────────────────────────────────────────────────────────

    /** One cycle + its recovery + its sleep → a whoop_cycles row.
     *
     * <p>The date derivation must reproduce {@code WhoopArchive.rowToCycle} exactly,
     * or the same physiological day lands on two different rows depending on which
     * source saw it: local wake date, falling back to cycle end, then cycle start. */
    static WhoopCycle toRow(Map<String, Object> cycle,
                            Map<Long, Map<String, Object>> recoveryByCycle,
                            Map<Long, Map<String, Object>> sleepByCycle) {
        Long cid = asLong(cycle.get("id"));
        Map<String, Object> rec = cid == null ? null : recoveryByCycle.get(cid);
        Map<String, Object> slp = cid == null ? null : sleepByCycle.get(cid);

        int offsetSec = offsetSeconds((String) cycle.get("timezone_offset"));
        String date = localDate(slp == null ? null : (String) slp.get("end"), offsetSec);
        if (date == null) {
            date = localDate((String) cycle.get("end"), offsetSec);
        }
        if (date == null) {
            date = localDate((String) cycle.get("start"), offsetSec);
        }
        if (date == null) {
            return null;   // an open cycle with no usable timestamp yet
        }

        WhoopCycle c = new WhoopCycle();
        c.date = date;
        c.source = "api";
        c.whoopCycleId = cid;
        c.apiUpdatedAt = newest((String) cycle.get("updated_at"),
                rec == null ? null : (String) rec.get("updated_at"),
                slp == null ? null : (String) slp.get("updated_at"));

        // cycle_start/cycle_end are deliberately NOT written here. They hold the
        // ZIP's "yyyy-MM-dd HH:mm:ss", and WhoopInsights.minutesOfDay parses with
        // exactly that formatter, returning null on failure. Writing the API's
        // ISO-8601 into them would make bedtime consistency silently degrade to
        // fewer nights with no error logged anywhere.

        // Only SCORED rows contribute metrics. A poll routinely catches this
        // morning's cycle as PENDING_SCORE; writing it would blank a previously
        // scored day, and the timestamp guard would NOT stop that because the
        // updated_at really is newer.
        if (scored(cycle)) {
            Map<String, Object> score = asMap(cycle.get("score"));
            c.dayStrain = asDouble(score.get("strain"));
            Double kj = asDouble(score.get("kilojoule"));
            // ZIP column is "Energy burned (cal)", which WHOOP reports as kcal.
            c.energyKcal = kj == null ? null : kj / 4.184;
        }
        if (rec != null && scored(rec)) {
            Map<String, Object> score = asMap(rec.get("score"));
            c.recoveryScore = asDouble(score.get("recovery_score"));
            c.hrvRmssdMs = asDouble(score.get("hrv_rmssd_milli"));
            c.rhrBpm = asDouble(score.get("resting_heart_rate"));
            c.spo2Pct = asDouble(score.get("spo2_percentage"));
            c.skinTempC = asDouble(score.get("skin_temp_celsius"));
        }
        if (slp != null && scored(slp)) {
            Map<String, Object> score = asMap(slp.get("score"));
            c.sleepPerformancePct = asDouble(score.get("sleep_performance_percentage"));
            c.sleepEfficiencyPct = asDouble(score.get("sleep_efficiency_percentage"));
            c.respiratoryRate = asDouble(score.get("respiratory_rate"));
            c.asleepH = asleepHours(asMap(score.get("stage_summary")));
        }
        return c;
    }

    /** Time actually asleep = light + slow-wave + REM, in hours.
     *
     * <p>stage_summary carries more sub-fields than these three (awake time, sleep
     * cycle counts, disturbances); summing only the asleep stages is what matches
     * the ZIP's "Asleep duration (min)". Returns null when none are present rather
     * than a misleading 0.0. */
    static Double asleepHours(Map<String, Object> stages) {
        Double light = asDouble(stages.get("total_light_sleep_time_milli"));
        Double sws = asDouble(stages.get("total_slow_wave_sleep_time_milli"));
        Double rem = asDouble(stages.get("total_rem_sleep_time_milli"));
        if (light == null && sws == null && rem == null) {
            return null;
        }
        double milli = (light == null ? 0 : light) + (sws == null ? 0 : sws) + (rem == null ? 0 : rem);
        return milli / 3_600_000.0;
    }

    static boolean scored(Map<String, Object> o) {
        return "SCORED".equals(o.get("score_state"));
    }

    /** ISO-8601 instant + a fixed offset → the local calendar date.
     *
     * <p>The ZIP path parses "yyyy-MM-dd HH:mm:ss" as UTC and shifts; this parses a
     * real instant and shifts. Different input formats, identical result — which is
     * the whole point, since both feed the same primary key. */
    static String localDate(String iso, int offsetSec) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso.strip())
                    .atOffset(ZoneOffset.ofTotalSeconds(offsetSec))
                    .toLocalDate().toString();
        } catch (java.time.DateTimeException e) {   // includes DateTimeParseException
            return null;
        }
    }

    /** WHOOP sends "-04:00"; ZoneOffset parses that directly. Absent → UTC. */
    static int offsetSeconds(String tz) {
        if (tz == null || tz.isBlank()) {
            return 0;
        }
        try {
            return ZoneOffset.of(tz.strip()).getTotalSeconds();
        } catch (java.time.DateTimeException e) {
            return 0;
        }
    }

    /** Lexicographically greatest ISO timestamp — ISO-8601 sorts chronologically. */
    static String newest(String... candidates) {
        String best = null;
        for (String c : candidates) {
            if (c != null && !c.isBlank() && (best == null || c.compareTo(best) > 0)) {
                best = c;
            }
        }
        return best;
    }

    // ── write ────────────────────────────────────────────────────────────────

    /** Upsert with source precedence. Returns {written, skipped}.
     *
     * <p>Per-row read-modify-write is fine here: a full backfill is a few thousand
     * rows and the incremental run is a handful. This never approaches the ~27k
     * round-trips that forced the ZIP importer's delete+batch-insert workaround. */
    int[] upsert(int aid, List<WhoopCycle> rows) {
        int written = 0;
        int skipped = 0;
        String now = PyJson.utcNowIso();
        for (WhoopCycle incoming : rows) {
            WhoopCycle stored = WhoopCycle.findById(new WhoopCycle.PK(aid, incoming.date));
            if (stored == null) {
                incoming.athleteId = aid;
                incoming.updatedAt = now;
                incoming.persist();
                written++;
                continue;
            }
            // An 'api' row is never overwritten by anything older. Within 'api',
            // only a newer observation wins; a re-fetch of unchanged data is a no-op.
            if ("api".equals(stored.source)
                    && stored.apiUpdatedAt != null
                    && incoming.apiUpdatedAt != null
                    && incoming.apiUpdatedAt.compareTo(stored.apiUpdatedAt) < 0) {
                skipped++;
                continue;
            }
            merge(stored, incoming, now);
            written++;
        }
        return new int[] {written, skipped};
    }

    /** Copy non-null metrics onto the stored row.
     *
     * <p>Null-skipping is the score-state guard's other half: an unscored cycle
     * arrives with null metrics, and those must not blank values the ZIP or an
     * earlier scored fetch already supplied. */
    private static void merge(WhoopCycle stored, WhoopCycle in, String now) {
        if (in.recoveryScore != null) stored.recoveryScore = in.recoveryScore;
        if (in.hrvRmssdMs != null) stored.hrvRmssdMs = in.hrvRmssdMs;
        if (in.rhrBpm != null) stored.rhrBpm = in.rhrBpm;
        if (in.dayStrain != null) stored.dayStrain = in.dayStrain;
        if (in.energyKcal != null) stored.energyKcal = in.energyKcal;
        if (in.spo2Pct != null) stored.spo2Pct = in.spo2Pct;
        if (in.skinTempC != null) stored.skinTempC = in.skinTempC;
        if (in.sleepPerformancePct != null) stored.sleepPerformancePct = in.sleepPerformancePct;
        if (in.sleepEfficiencyPct != null) stored.sleepEfficiencyPct = in.sleepEfficiencyPct;
        if (in.respiratoryRate != null) stored.respiratoryRate = in.respiratoryRate;
        if (in.asleepH != null) stored.asleepH = in.asleepH;
        if (in.whoopCycleId != null) stored.whoopCycleId = in.whoopCycleId;
        if (in.apiUpdatedAt != null) stored.apiUpdatedAt = in.apiUpdatedAt;
        stored.source = "api";
        stored.updatedAt = now;
    }

    /** Test seam for {@link #merge}, which is private because nothing in
     * production should merge rows outside the upsert's precedence check. */
    void upsertMergeForTest(WhoopCycle stored, WhoopCycle incoming) {
        merge(stored, incoming, PyJson.utcNowIso());
    }

    // ── coercion ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    static Double asDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    static Long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }
}
