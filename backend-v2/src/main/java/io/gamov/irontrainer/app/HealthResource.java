package io.gamov.irontrainer.app;

import io.gamov.irontrainer.util.Params;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

/** GET /api/health — liveness by default; ?deep=1 also checks DB connectivity
 * (503 when down). Port of main.health. Unauthenticated (a readiness probe). */
@Path("/api/health")
public class HealthResource {

    private static final Logger LOG = Logger.getLogger(HealthResource.class);

    @Inject
    DataSource dataSource;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response health(@QueryParam("deep") String deepParam) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("version", AppInfo.VERSION);
        if (Params.boolOr(deepParam, false)) {
            // Probe on a raw pooled connection OUTSIDE any JTA transaction (like
            // FastAPI's separate engine connection). NOT @Transactional: a failed
            // SELECT would mark the request transaction rollback-only and the
            // interceptor would turn this intended 503 into a bodyless 500.
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                st.execute("SELECT 1");
                out.put("database", "ok");
            } catch (Exception e) {
                // Full error to the logs only — DB errors can echo the DSN; this
                // endpoint is unauthenticated.
                LOG.errorf(e, "Deep health check failed");
                out.put("status", "degraded");
                out.put("database", "error");
                out.put("detail", "database unreachable — see server logs");
                return Response.status(503).entity(out).build();
            }
        }
        return Response.ok(out).build();
    }
}
