package io.gamov.irontrainer.admin;

import io.gamov.irontrainer.jobs.Job;
import io.gamov.irontrainer.jobs.JobRunner;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Admin/ops console API (bean gfb3). Password-gated (shared ADMIN_PASSWORD → signed
 * admin_session cookie), decoupled from athlete accounts. login/logout are open; the
 * data endpoints are @RequireAdmin (AdminAuthFilter 401s without a valid session).
 * The data endpoints query ACROSS all athletes — they never use CurrentAthlete.
 */
@Path("/api/admin")
public class AdminResource {

    private static final int ERROR_PREVIEW = 300;

    @Inject
    JobRunner jobRunner;

    @ConfigProperty(name = "irontrainer.admin-password")
    Optional<String> adminPassword;
    @ConfigProperty(name = "irontrainer.session-secret")
    Optional<String> sessionSecret;
    @ConfigProperty(name = "irontrainer.cookie-secure")
    boolean cookieSecure;

    public record LoginRequest(String password) {}

    // ── auth (open) ─────────────────────────────────────────────────────────

    @POST
    @Path("/login")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response login(LoginRequest req) {
        String configured = adminPassword.filter(s -> !s.isBlank()).orElse(null);
        String secret = sessionSecret.filter(s -> !s.isBlank()).orElse(null);
        if (configured == null || secret == null) {
            // No ADMIN_PASSWORD (or no signing secret) → admin console disabled.
            throw new WebApplicationException("Admin console is not configured.", 503);
        }
        if (req == null || req.password() == null
                || !MessageDigest.isEqual(req.password().getBytes(StandardCharsets.UTF_8),
                        configured.getBytes(StandardCharsets.UTF_8))) {
            throw new WebApplicationException("Invalid admin password.", 401);
        }
        return Response.ok(Map.of("ok", true))
                .header("Set-Cookie", cookie(AdminSession.sign(secret), AdminSession.TTL_SECONDS))
                .build();
    }

    @POST
    @Path("/logout")
    @Produces(MediaType.APPLICATION_JSON)
    public Response logout() {
        return Response.ok(Map.of("ok", true))
                .header("Set-Cookie", cookie("", 0))
                .build();
    }

    // ── data (admin-only) ───────────────────────────────────────────────────

    /** Cross-athlete job list, newest-first, filterable + paginated. Lightweight rows
     * (truncated error, has_result flag); use /jobs/{id} for the full result/error. */
    @GET
    @Path("/jobs")
    @Produces(MediaType.APPLICATION_JSON)
    @RequireAdmin
    public Map<String, Object> jobs(@QueryParam("kind") String kind,
                                    @QueryParam("status") String status,
                                    @QueryParam("athlete_id") String athleteId,
                                    @QueryParam("limit") @DefaultValue("50") int limit,
                                    @QueryParam("offset") @DefaultValue("0") int offset) {
        StringBuilder where = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();
        if (kind != null && !kind.isBlank()) {
            where.append("kind = :kind");
            params.put("kind", kind);
        }
        if (status != null && !status.isBlank()) {
            where.append(where.isEmpty() ? "" : " and ").append("status = :status");
            params.put("status", status);
        }
        Integer aid = parseIntOrNull(athleteId);   // free-text filter → ignore if non-numeric
        if (aid != null) {
            where.append(where.isEmpty() ? "" : " and ").append("athleteId = :aid");
            params.put("aid", aid);
        }
        String q = (where.isEmpty() ? "" : where + " ") + "order by id desc";
        PanacheQuery<Job> query = params.isEmpty() ? Job.find(q) : Job.find(q, params);

        long total = query.count();
        int lim = Math.min(Math.max(limit, 1), 200);
        int off = Math.max(offset, 0);
        List<Job> rows = total == 0 ? List.of() : query.range(off, off + lim - 1).list();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Job j : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", j.id);
            m.put("athlete_id", j.athleteId);
            m.put("kind", j.kind);
            m.put("status", j.status);
            m.put("created_at", j.createdAt);
            m.put("started_at", j.startedAt);
            m.put("finished_at", j.finishedAt);
            m.put("error", preview(j.error));
            out.add(m);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jobs", out);
        resp.put("total", total);
        resp.put("limit", lim);
        resp.put("offset", off);
        return resp;
    }

    /** Full job detail incl. parsed result + full error. */
    @GET
    @Path("/jobs/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @RequireAdmin
    public Map<String, Object> job(@PathParam("id") int id) {
        Job j = Job.findById(id);
        if (j == null) {
            throw new WebApplicationException("Job not found.", 404);
        }
        return jobRunner.jobDict(j);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String cookie(String value, long maxAge) {
        return AdminSession.COOKIE + "=" + value
                + "; path=/; Max-Age=" + maxAge + "; httponly; samesite=lax"
                + (cookieSecure ? "; secure" : "");
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String preview(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= ERROR_PREVIEW ? error : error.substring(0, ERROR_PREVIEW) + "…";
    }
}
