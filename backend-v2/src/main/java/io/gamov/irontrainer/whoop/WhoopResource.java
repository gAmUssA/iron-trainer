package io.gamov.irontrainer.whoop;

import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.jobs.JobRunner;
import io.gamov.irontrainer.util.Params;
import io.gamov.irontrainer.util.PyJson;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

/** WHOOP member data-export import + per-day series. The ZIP path needs no
 * OAuth app approval (WHOOP caps unapproved API apps at 10 users) — the member
 * exports their own data from the WHOOP app and uploads it here. */
@Path("/api/whoop")
public class WhoopResource {

    private static final Logger LOG = Logger.getLogger(WhoopResource.class);

    // WHOOP exports are CSVs — a few MB even for years of data.
    private static final long MAX_UPLOAD_BYTES = 200L * 1024 * 1024;

    @Inject
    CurrentAthlete current;

    @Inject
    JobRunner jobs;

    @Inject
    WhoopAi ai;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.langchain4j.anthropic.api-key")
    java.util.Optional<String> apiKey;

    /** POST /api/whoop/import — parse an uploaded WHOOP export ZIP and upsert
     * one row per (athlete, day). Re-uploading a newer export is idempotent:
     * same days are overwritten, new days appended. Sync — even a multi-year
     * export parses in well under a second. */
    @POST
    @Path("/import")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Map<String, Object> importArchive(@RestForm("file") FileUpload file) {
        int aid = current.require();
        if (file == null) {
            throw new BadRequestException("file is required");
        }
        if (file.size() > MAX_UPLOAD_BYTES) {
            throw new ClientErrorException("Archive exceeds the 200 MB upload limit.", 413);
        }
        WhoopArchive.Export export;
        try {
            export = WhoopArchive.parse(file.uploadedFile());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
        List<WhoopCycle> cycles = export.cycles();
        // The export is newest-first; sort oldest-first so the summary reads
        // naturally and, on duplicate days (e.g. travel splitting a day across
        // two cycles), the LATER cycle wins the upsert.
        cycles.sort(Comparator.comparing((WhoopCycle c) -> c.date)
                .thenComparing(c -> c.cycleStart == null ? "" : c.cycleStart));
        String now = Instant.now().toString();
        int upserted = QuarkusTransaction.requiringNew().call(() -> {
            int n = 0;
            for (WhoopCycle c : cycles) {
                c.athleteId = aid;
                c.updatedAt = now;
                // merge = insert-or-update by the (athlete_id, date) PK.
                WhoopCycle.getEntityManager().merge(c);
                n++;
            }
            for (WhoopJournalEntry j : export.journal()) {
                j.athleteId = aid;
                j.updatedAt = now;
                WhoopJournalEntry.getEntityManager().merge(j);
            }
            return n;
        });
        String first = cycles.isEmpty() ? null : cycles.get(0).date;
        String last = cycles.isEmpty() ? null : cycles.get(cycles.size() - 1).date;
        LOG.infof("Athlete %d imported WHOOP export: %d cycles (%s → %s), %d journal answers.",
                aid, upserted, first, last, export.journal().size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cycles", upserted);
        out.put("journal_answers", export.journal().size());
        out.put("first_date", first);
        out.put("last_date", last);
        return out;
    }

    /** GET /api/whoop/cycles?days=N — recent WHOOP days, newest first (mirrors
     * /api/health/recovery so the overlay page can zip the two by date). */
    @GET
    @Path("/cycles")
    @jakarta.transaction.Transactional
    public Map<String, Object> cycles(@QueryParam("days") String daysParam) {
        int aid = current.require();
        int days = daysParam == null ? 35 : Params.intParam(daysParam);
        int limit = Math.max(1, Math.min(days, 365));
        List<WhoopCycle> rows = WhoopCycle
                .<WhoopCycle>find("athleteId = ?1 order by date desc", aid).page(0, limit).list();
        List<Map<String, Object>> out = new ArrayList<>();
        for (WhoopCycle c : rows) {
            out.add(c.toRow());
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("days", out);
        return resp;
    }

    /** GET /api/whoop/insights — deterministic stats (behavior correlations,
     * bedtime consistency, 28-day trend) + the persisted AI analysis, if any. */
    @GET
    @Path("/insights")
    @jakarta.transaction.Transactional
    public Map<String, Object> insights() {
        int aid = current.require();
        Map<String, Object> out = WhoopInsights.compute(allCycles(aid), allJournal(aid));
        WhoopInsight saved = WhoopInsight.findById(aid);
        Map<String, Object> analysis = null;
        if (saved != null && saved.analysisMd != null) {
            analysis = new LinkedHashMap<>();
            analysis.put("text", saved.analysisMd);
            analysis.put("created_at", saved.createdAt);
        }
        out.put("analysis", analysis);
        out.put("ai_available", llmAvailable());
        out.put("analyze_runs_left", runsLeft(saved, utcToday()));
        return out;
    }

    /** POST /api/whoop/insights/analyze — run the staged AI analysis over the
     * computed insights + last 90 days, persist it, return it. The LLM call can
     * take up to 60s, so ?async=1 runs it as a job (kind "whoop_insights"),
     * mirroring nutrition regenerate. */
    @POST
    @Path("/insights/analyze")
    public Map<String, Object> analyze(@QueryParam("async") String asyncParam) {
        int aid = current.require();
        if (Params.boolOr(asyncParam, false)) {
            Map<String, Object> env = new LinkedHashMap<>();
            env.put("job", jobs.submit(aid, "whoop_insights", () -> runAnalysis(aid)));
            return env;
        }
        return runAnalysis(aid);
    }

    // The LLM call is paid and slow — cap it per athlete per UTC day.
    static final int MAX_ANALYSES_PER_DAY = 2;

    private Map<String, Object> runAnalysis(int aid) {
        String today = utcToday();
        // Rate gate first (429 beats 503 — and a keyless env never burns a run).
        if (runsLeft(QuarkusTransaction.requiringNew().call(() -> WhoopInsight.findById(aid)), today) <= 0) {
            throw new ClientErrorException(
                    "Analysis is limited to " + MAX_ANALYSES_PER_DAY + " runs per day — try again tomorrow.", 429);
        }
        if (!llmAvailable()) {
            throw new jakarta.ws.rs.ServiceUnavailableException("ANTHROPIC_API_KEY is not configured.");
        }
        // Spend the run BEFORE the paid call — a failure burns a slot, but the
        // reverse order would let retry-hammering multiply API cost. Concurrent
        // doubles are already blocked by the same-kind job.
        QuarkusTransaction.requiringNew().run(() -> {
            WhoopInsight row = WhoopInsight.findById(aid);
            if (row == null) {
                row = new WhoopInsight();
                row.athleteId = aid;
                row.persist();
            }
            row.runsCount = today.equals(row.runsDate) ? (row.runsCount == null ? 0 : row.runsCount) + 1 : 1;
            row.runsDate = today;
        });
        // DB reads in a tx; the LLM call runs OUTSIDE it (external, slow).
        record Ctx(Map<String, Object> insights, String recentDays) {}
        Ctx ctx = QuarkusTransaction.requiringNew().call(() -> {
            List<WhoopCycle> cycles = allCycles(aid);
            if (cycles.isEmpty()) {
                throw new BadRequestException("No WHOOP data — upload an export first.");
            }
            return new Ctx(WhoopInsights.compute(cycles, allJournal(aid)), recentDaysBlock(cycles));
        });
        String text = ai.analyze(PyJson.dumps(ctx.insights()), ctx.recentDays());
        String now = Instant.now().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            WhoopInsight row = WhoopInsight.findById(aid);
            row.analysisMd = text;
            row.createdAt = now;
        });
        LOG.infof("Athlete %d WHOOP AI analysis generated (%d chars).", aid, text == null ? 0 : text.length());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", text);
        out.put("created_at", now);
        return out;
    }

    /** Last 90 data days, oldest first, one compact line per day for the prompt. */
    static String recentDaysBlock(List<WhoopCycle> cycles) {
        List<WhoopCycle> sorted = new ArrayList<>(cycles);
        sorted.sort(Comparator.comparing(c -> c.date));
        List<WhoopCycle> tail = sorted.subList(Math.max(0, sorted.size() - 90), sorted.size());
        StringBuilder b = new StringBuilder();
        for (WhoopCycle c : tail) {
            b.append(c.date).append(", ").append(dash(c.recoveryScore)).append(", ")
                    .append(dash(c.hrvRmssdMs)).append(", ").append(dash(c.rhrBpm)).append(", ")
                    .append(dash(c.dayStrain)).append(", ").append(dash(WhoopInsights.round1(c.asleepH)))
                    .append(", ").append(dash(c.sleepPerformancePct)).append('\n');
        }
        return b.toString();
    }

    private static String dash(Double v) {
        return v == null ? "-" : (v == Math.floor(v) ? String.valueOf(v.intValue()) : String.valueOf(v));
    }

    private static List<WhoopCycle> allCycles(int aid) {
        return WhoopCycle.list("athleteId = ?1", aid);
    }

    private static List<WhoopJournalEntry> allJournal(int aid) {
        return WhoopJournalEntry.list("athleteId = ?1", aid);
    }

    private static String utcToday() {
        return Instant.now().toString().substring(0, 10);
    }

    static int runsLeft(WhoopInsight row, String utcToday) {
        if (row == null || !utcToday.equals(row.runsDate) || row.runsCount == null) {
            return MAX_ANALYSES_PER_DAY;
        }
        return Math.max(0, MAX_ANALYSES_PER_DAY - row.runsCount);
    }

    // Same key sentinel logic as NutritionLlm: the langchain4j extension needs a
    // non-empty value to boot, "no-key" means no real key is configured.
    private boolean llmAvailable() {
        return apiKey.map(k -> !k.isBlank() && !k.equals("no-key")).orElse(false);
    }
}
