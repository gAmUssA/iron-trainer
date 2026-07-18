package io.gamov.irontrainer.observability;

import io.gamov.irontrainer.auth.CurrentAthlete;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/** One structured log line per API request — method, path, athlete, status,
 * duration_ms — so live traffic (especially the write endpoints flipped to
 * backend-v2) is investigable beyond the raw access log, which carries no athlete
 * or severity. Logging only: it never touches the response, so it is parity-safe.
 *
 * Severity is tiered so prod stays quiet but failures and mutations stand out:
 *   5xx                     → ERROR (unexpected failure — always surface)
 *   writes (POST/PUT/…)     → INFO  (every mutation, incl. its outcome status)
 *   reads / 4xx             → DEBUG (expected + already in the access log)
 *
 * Low @Priority so the request side runs first (START is set before any other
 * filter can abort → accurate duration); as a class-level priority that also
 * makes the response side run last, seeing the final status. */
@Provider
@Priority(100)
public class RequestLogFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger LOG = Logger.getLogger("io.gamov.irontrainer.request");
    private static final String START = "io.gamov.request.startNanos";

    @Inject
    CurrentAthlete current;

    @Override
    public void filter(ContainerRequestContext req) {
        req.setProperty(START, System.nanoTime());
    }

    @Override
    public void filter(ContainerRequestContext req, ContainerResponseContext res) {
        // Observability must NEVER affect the response: swallow anything.
        try {
            String method = req.getMethod();
            String path = req.getUriInfo().getPath();
            int status = res.getStatus();
            Object start = req.getProperty(START);
            long ms = start instanceof Long s ? (System.nanoTime() - s) / 1_000_000L : -1L;
            Integer athlete = athleteOrNull();
            // %s for the athlete prints "null" when unauthenticated — intended.
            LOG.logf(level(status, method), "%s %s athlete=%s -> %d (%dms)",
                    method, path, athlete, status, ms);
        } catch (Throwable t) {
            LOG.debug("request-log filter failed (ignored)", t);
        }
    }

    private Integer athleteOrNull() {
        try {
            return current.idOrNull();
        } catch (RuntimeException e) {
            // Request-scoped bean may be unavailable on an aborted/edge request.
            return null;
        }
    }

    /** Pure severity decision (unit-tested): 5xx→ERROR, writes→INFO, else→DEBUG. */
    static Logger.Level level(int status, String method) {
        if (status >= 500) return Logger.Level.ERROR;
        if (isWrite(method)) return Logger.Level.INFO;
        return Logger.Level.DEBUG;
    }

    private static boolean isWrite(String method) {
        return "POST".equals(method) || "PUT".equals(method)
                || "DELETE".equals(method) || "PATCH".equals(method);
    }
}
