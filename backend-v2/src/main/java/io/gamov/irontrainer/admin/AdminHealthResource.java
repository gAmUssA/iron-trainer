package io.gamov.irontrainer.admin;

import io.gamov.irontrainer.jobs.Job;
import io.gamov.irontrainer.util.PyJson;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
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

        List<Map<String, Object>> kinds = new ArrayList<>();
        for (Map.Entry<String, KindStats> e : byKind.entrySet()) {
            kinds.add(e.getValue().toMap(e.getKey()));
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
