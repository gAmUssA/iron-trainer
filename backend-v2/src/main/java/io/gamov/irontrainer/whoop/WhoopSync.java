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

    /** The ZIP's timestamp shape, which WhoopInsights.minutesOfDay expects. */
    private static final java.time.format.DateTimeFormatter ZIP_TS =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);

    /** WHOOP caps page size at 25; asking for more is rejected, not clamped. */
    private static final int PAGE = 25;

    /** Pause between pages. WHOOP allows 100 requests/minute = one per 600 ms;
     * 700 ms leaves headroom for the three collections a sync walks plus any
     * token refresh. A five-year backfill is ~230 requests, so this costs about
     * three minutes — acceptable for something that runs once at connect and then
     * only on a 3-day incremental window. */
    private static final long PAGE_PAUSE_MS = 700;

    /** 429 handling: escalating backoff, a few attempts, then give up and let the
     * caller report it. The daily job will try again tomorrow. */
    private static final int RATE_LIMIT_RETRIES = 4;
    private static final long RATE_LIMIT_BACKOFF_MS = 5_000;

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

    /** The WHOOP member id seen on the fetched records, if any. Lets the caller
     * stamp whoop_user_id WITHOUT the read:profile scope — cycle records already
     * carry user_id, so calling /v2/user/profile/basic would mean requesting a
     * scope we do not otherwise need (and would 403 without it). */
    public static Long memberId(List<Map<String, Object>> records) {
        for (Map<String, Object> r : records) {
            Long id = asLong(r.get("user_id"));
            if (id != null) {
                return id;
            }
        }
        return null;
    }

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

    /** Fill the gap between what is already stored and now — the right default
     * when connecting, and usually seconds rather than minutes.
     *
     * <p>The blunt alternative, a five-year full walk on every connect, ignores the
     * fact that most athletes arrive here having ALREADY uploaded the export ZIP.
     * Re-fetching years of days that are already on disk costs ~180 paged requests
     * and, at the 700ms pacing WHOOP's rate limit forces, minutes of wall clock —
     * to rewrite rows that were already correct.
     *
     * <p>So the start date comes from the newest row on file for this athlete,
     * regardless of source: a ZIP-imported day counts as covered. Two bounds on it:
     * <ul>
     *   <li>Nothing on file at all → there is no gap to fill, only a history to
     *       fetch, so fall back to the full window.</li>
     *   <li>Never reach back further than the full window would, so an athlete with
     *       one ancient row does not trigger an unbounded walk.</li>
     * </ul>
     *
     * <p>The overlap re-reads the last few days deliberately. WHOOP scores recovery
     * the morning after, sleep can be edited, and an export ZIP's final day is
     * frequently partial — without it the newest stored day stays stale forever,
     * because a gap fill that starts strictly after it never looks at it again.
     * Re-reading is free: the upsert is idempotent and skips unchanged rows. */
    public Result runCatchUp(int aid) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate earliest = today.minusYears(historyYears);
        String newest = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() ->
                WhoopCycle.<WhoopCycle>find("athleteId = ?1 order by date desc", aid)
                        .firstResultOptional().map(c -> c.date).orElse(null));
        // The ceiling matters as much as the floor. Stored dates come from a
        // user-supplied export and are NOT bounded to today — one row dated 2099
        // would push the start into the future, so every request would ask WHOOP
        // for an empty window and the athlete would never sync again, silently and
        // permanently. Clamping to the ordinary incremental start means the worst a
        // bogus future row can do is degrade catch-up to a normal daily sync.
        LocalDate latestSensibleStart = today.minusDays(INCREMENTAL_LOOKBACK_DAYS);
        LocalDate from = earliest;
        if (newest != null) {
            try {
                LocalDate gapStart = LocalDate.parse(newest).minusDays(INCREMENTAL_LOOKBACK_DAYS);
                if (gapStart.isBefore(earliest)) {
                    gapStart = earliest;
                }
                if (gapStart.isAfter(latestSensibleStart)) {
                    gapStart = latestSensibleStart;
                }
                from = gapStart;
            } catch (RuntimeException e) {
                // An unparseable stored date is not worth failing the sync over;
                // the full window is always a correct, if slower, answer.
                LOG.warnf("Unparseable newest whoop_cycles date %s for athlete %d — "
                        + "falling back to the full window.", newest, aid);
            }
        }
        LOG.infof("WHOOP catch-up for athlete %d from %s (newest stored day: %s).",
                aid, from, newest == null ? "none" : newest);
        return sync(aid, from.atStartOfDay().toInstant(ZoneOffset.UTC).toString(), null);
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

        // Stamp the WHOOP member from data we already have, so a reconnect as a
        // DIFFERENT member is visible instead of silently blending two people's
        // recovery history. Doing it here rather than at connect avoids needing the
        // read:profile scope at all.
        Long member = memberId(cycles);
        int[] counts = QuarkusTransaction.requiringNew().call(() -> {
            if (member != null) {
                io.gamov.irontrainer.athlete.Athlete a =
                        io.gamov.irontrainer.athlete.Athlete.findById(aid);
                if (a != null) {
                    if (a.whoopUserId != null && !a.whoopUserId.equals(member)) {
                        LOG.warnf("Athlete %d is syncing WHOOP member %d but previously "
                                + "synced %d — existing WHOOP history belongs to the "
                                + "previous member.", aid, member, a.whoopUserId);
                    }
                    a.whoopUserId = member;
                }
            }
            return upsert(aid, rows);
        });
        LOG.infof("WHOOP sync: athlete=%d cycles=%d written=%d skipped=%d from=%s",
                aid, cycles.size(), counts[0], counts[1], startIso);
        return new Result(cycles.size(), counts[0], counts[1], startIso, endIso);
    }

    // ── read ─────────────────────────────────────────────────────────────────

    private interface Pager {
        Map<String, Object> get(String nextToken);
    }

    /** Walk a cursor-paginated collection to the end, paced under the rate limit
     * and retrying a 429.
     *
     * <p>Found by running a real backfill: WHOOP allows 100 requests/minute and a
     * five-year walk is ~230 requests, so firing them as fast as the client can go
     * earns a 429 partway through — for us, on the SECOND collection, after the
     * cycles walk had already spent the budget. Unpaced, a full backfill can never
     * complete. */
    private static List<Map<String, Object>> page(Pager pager) {
        List<Map<String, Object>> all = new ArrayList<>();
        String token = null;
        for (int i = 0; i < MAX_PAGES; i++) {
            Map<String, Object> resp = withRetry(pager, token);
            all.addAll(WhoopApi.records(resp));
            token = WhoopApi.nextToken(resp);
            if (token == null) {
                return all;
            }
            throttle(PAGE_PAUSE_MS);
        }
        LOG.warnf("WHOOP pagination hit the %d-page cap — results may be truncated.", MAX_PAGES);
        return all;
    }

    /** One page, retrying on 429 with escalating backoff.
     *
     * <p>WHOOP publishes no backoff guidance and it is unconfirmed whether it sends
     * Retry-After, so this uses fixed escalating waits rather than pretending to
     * honour a header that may not be there. Anything other than 429 propagates
     * immediately — a 401 or 404 is not going to fix itself by waiting. */
    private static Map<String, Object> withRetry(Pager pager, String token) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < RATE_LIMIT_RETRIES; attempt++) {
            try {
                return pager.get(token);
            } catch (RuntimeException e) {
                if (!isRateLimited(e)) {
                    throw e;
                }
                last = e;
                long wait = RATE_LIMIT_BACKOFF_MS * (attempt + 1L);
                LOG.warnf("WHOOP rate-limited (429); backing off %d ms (attempt %d/%d).",
                        wait, attempt + 1, RATE_LIMIT_RETRIES);
                throttle(wait);
            }
        }
        throw last;
    }

    /** The REST client wraps the status in the message rather than exposing a typed
     * 429, so match on the status code text. Narrow, but it is what the client gives us. */
    private static boolean isRateLimited(RuntimeException e) {
        String msg = e.getMessage();
        return msg != null && msg.contains("429");
    }

    private static void throttle(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("WHOOP sync interrupted", e);
        }
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

        // cycle_start/cycle_end are stored in the ZIP's "yyyy-MM-dd HH:mm:ss" shape,
        // NOT raw ISO-8601. WhoopInsights.minutesOfDay parses with exactly that
        // formatter and returns null on failure, so raw ISO here would silently
        // drop every API-only day out of bedtime consistency. Omitting the fields
        // entirely has the same effect, which is the trap an earlier revision fell
        // into — the fix is to convert, not to skip.
        c.cycleStart = zipTimestamp((String) cycle.get("start"));
        c.cycleEnd = zipTimestamp((String) cycle.get("end"));

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

    /** ISO-8601 instant → the "yyyy-MM-dd HH:mm:ss" UTC form the ZIP importer
     * writes and WhoopInsights.minutesOfDay parses. Both sources must agree on the
     * representation or bedtime consistency silently loses half its nights. */
    static String zipTimestamp(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return ZIP_TS.format(Instant.parse(iso.strip()).atOffset(ZoneOffset.UTC));
        } catch (java.time.DateTimeException e) {
            return null;
        }
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
        if (in.cycleStart != null) stored.cycleStart = in.cycleStart;
        if (in.cycleEnd != null) stored.cycleEnd = in.cycleEnd;
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
