package io.gamov.irontrainer.admin;

import io.gamov.irontrainer.health.HealthIngestLog;
import io.gamov.irontrainer.jobs.Job;
import io.gamov.irontrainer.util.PyJson;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Parameters;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin sync-health telemetry (bean j41l): "which backend/sync is failing" at a
 * glance. Per-kind status counts + failure rate over a window, plus a recent-
 * failures feed. Behind @RequireAdmin. Timestamps are ISO strings that sort
 * lexicographically = chronologically, so the window is a plain string compare.
 */
@Path("/api/admin")
public class AdminHealthResource {

    @GET
    @Path("/health/jobs")
    @Produces(MediaType.APPLICATION_JSON)
    @RequireAdmin
    public Map<String, Object> jobHealth(@QueryParam("days") @DefaultValue("7") int daysParam) {
        int days = Math.min(90, Math.max(1, daysParam));
        String since = PyJson.utcIsoDaysAgo(days);

        // Counts per (kind, status) in one grouped query — no row loading.
        List<Object[]> rows = Job.getEntityManager()
                .createQuery("select j.kind, j.status, count(j) from Job j where j.createdAt >= ?1 group by j.kind, j.status", Object[].class)
                .setParameter(1, since).getResultList();

        Map<String, KindStats> byKind = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String kind = (String) r[0];
            String status = (String) r[1];
            long n = ((Number) r[2]).longValue();
            byKind.computeIfAbsent(kind, k -> new KindStats()).add(status, n);
        }

        // Duration percentiles per kind (bean og06): load the jobs in the window
        // that actually ran (both started_at + finished_at set) and compute p50/p95
        // of finished-started in Java. Small table — fine to load; ponytail: switch
        // to Postgres percentile_cont(order by finished::ts - started::ts) if it grows.
        Map<String, List<Long>> durationsByKind = new LinkedHashMap<>();
        List<Job> timed = Job.<Job>find(
                "createdAt >= ?1 and startedAt is not null and finishedAt is not null", since).list();
        for (Job j : timed) {
            Long ms = durationMs(j.startedAt, j.finishedAt);
            if (ms != null) {
                durationsByKind.computeIfAbsent(j.kind, k -> new ArrayList<>()).add(ms);
            }
        }
        durationsByKind.values().forEach(java.util.Collections::sort);

        List<Map<String, Object>> kinds = new ArrayList<>();
        for (Map.Entry<String, KindStats> e : byKind.entrySet()) {
            Map<String, Object> m = e.getValue().toMap(e.getKey());
            List<Long> ds = durationsByKind.get(e.getKey());
            m.put("p50_ms", percentile(ds, 50));
            m.put("p95_ms", percentile(ds, 95));
            m.put("timed", ds == null ? 0 : ds.size());
            kinds.add(m);
        }
        // Worst first: highest failure rate, then most failures, then busiest.
        kinds.sort((a, b) -> {
            int c = Double.compare((double) b.get("failure_rate"), (double) a.get("failure_rate"));
            if (c != 0) return c;
            c = Long.compare((long) b.get("failed"), (long) a.get("failed"));
            if (c != 0) return c;
            return Long.compare((long) b.get("total"), (long) a.get("total"));
        });

        // Recent failures feed — newest first, within the same window.
        List<Map<String, Object>> recentFailures = new ArrayList<>();
        for (Job j : Job.<Job>find("status = ?1 and createdAt >= ?2 order by id desc", "failed", since).page(0, 20).list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", j.id);
            m.put("kind", j.kind);
            m.put("athlete_id", j.athleteId);
            m.put("created_at", j.createdAt);
            m.put("finished_at", j.finishedAt);
            m.put("error", j.error == null || j.error.length() <= 300 ? j.error : j.error.substring(0, 300) + "…");
            recentFailures.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window_days", days);
        out.put("since", since);
        out.put("kinds", kinds);
        out.put("recent_failures", recentFailures);
        return out;
    }

    /**
     * Health-ingest audit feed (bean j05e): recent POST /api/health/ingest events
     * over a window (filterable by athlete/source/ok) + the last ingest per
     * (athlete, source) regardless of window (for spotting a client that went quiet).
     */
    @GET
    @Path("/health/ingests")
    @Produces(MediaType.APPLICATION_JSON)
    @RequireAdmin
    public Map<String, Object> ingests(
            @QueryParam("days") @DefaultValue("7") int daysParam,
            @QueryParam("athlete_id") String athleteId,
            @QueryParam("source") String source,
            @QueryParam("ok") String ok,
            @QueryParam("limit") @DefaultValue("50") int limitParam,
            @QueryParam("offset") @DefaultValue("0") int offsetParam) {
        int days = Math.min(365, Math.max(1, daysParam));
        int lim = Math.min(200, Math.max(1, limitParam));
        int off = Math.max(0, offsetParam);
        String since = PyJson.utcIsoDaysAgo(days);

        StringBuilder q = new StringBuilder("receivedAt >= :since");
        Parameters params = Parameters.with("since", since);
        if (athleteId != null && !athleteId.isBlank()) {
            Integer aid = parseIntOrNull(athleteId);
            if (aid == null) return ingestPage(days, since, lim, off, 0L, List.of(), lastBySource());
            q.append(" and athleteId = :aid");
            params.and("aid", aid);
        }
        if (source != null && !source.isBlank()) {
            q.append(" and source = :source");
            params.and("source", source.trim());
        }
        if ("true".equalsIgnoreCase(ok) || "false".equalsIgnoreCase(ok)) {
            q.append(" and ok = :ok");
            params.and("ok", Boolean.valueOf(ok));
        }

        PanacheQuery<HealthIngestLog> query = HealthIngestLog.find(q + " order by id desc", params);
        long total = query.count();
        List<Map<String, Object>> items = new ArrayList<>();
        for (HealthIngestLog l : query.range(off, off + lim - 1).<HealthIngestLog>list()) {
            items.add(ingestRow(l));
        }
        return ingestPage(days, since, lim, off, total, items, lastBySource());
    }

    /** Last ingest per (athlete, source), regardless of window — max(id) group-by
     * then load, so a client that stopped posting still shows its last event. */
    private static List<Map<String, Object>> lastBySource() {
        List<Integer> lastIds = HealthIngestLog.getEntityManager()
                .createQuery("select max(l.id) from HealthIngestLog l group by l.athleteId, l.source", Integer.class)
                .getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        if (!lastIds.isEmpty()) {
            for (HealthIngestLog l : HealthIngestLog.<HealthIngestLog>list("id in ?1 order by id desc", lastIds)) {
                out.add(ingestRow(l));
            }
        }
        return out;
    }

    private static Map<String, Object> ingestPage(int days, String since, int lim, int off,
            long total, List<Map<String, Object>> items, List<Map<String, Object>> lastBySource) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window_days", days);
        out.put("since", since);
        out.put("total", total);
        out.put("limit", lim);
        out.put("offset", off);
        out.put("ingests", items);
        out.put("last_by_source", lastBySource);
        return out;
    }

    private static Map<String, Object> ingestRow(HealthIngestLog l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.id);
        m.put("athlete_id", l.athleteId);
        m.put("source", l.source);
        m.put("received_at", l.receivedAt);
        m.put("ok", l.ok);
        m.put("days_stored", l.daysStored);
        m.put("records", l.records);
        m.put("unknown_metrics", l.unknownMetrics);
        m.put("bad_dates", l.badDates);
        m.put("byte_size", l.byteSize);
        m.put("error", l.error);
        return m;
    }

    private static Integer parseIntOrNull(String s) {
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** finished - started in millis, or null if either can't be parsed or the span
     * is negative (clock skew / bad data). Timestamps are ISO-8601 with offset. */
    static Long durationMs(String started, String finished) {
        try {
            long ms = ChronoUnit.MILLIS.between(OffsetDateTime.parse(started), OffsetDateTime.parse(finished));
            return ms < 0 ? null : ms;
        } catch (Exception e) {
            return null;
        }
    }

    /** Nearest-rank percentile of a pre-sorted list, or null if empty. */
    static Long percentile(List<Long> sorted, double p) {
        if (sorted == null || sorted.isEmpty()) {
            return null;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    /** Per-kind tally. "other" catches queued/anything non-terminal beyond the known set. */
    private static final class KindStats {
        long total, succeeded, failed, running, queued, other;

        void add(String status, long n) {
            total += n;
            switch (status == null ? "" : status) {
                case "succeeded" -> succeeded += n;
                case "failed" -> failed += n;
                case "running" -> running += n;
                case "queued" -> queued += n;
                default -> other += n;
            }
        }

        Map<String, Object> toMap(String kind) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("total", total);
            m.put("succeeded", succeeded);
            m.put("failed", failed);
            m.put("running", running);
            m.put("queued", queued);
            m.put("other", other);
            m.put("failure_rate", total == 0 ? 0.0 : Math.round((double) failed / total * 1000) / 1000.0);
            return m;
        }
    }
}
