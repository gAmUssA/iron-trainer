package io.gamov.irontrainer.idempotency;

import io.gamov.irontrainer.auth.CurrentAthlete;
import io.gamov.irontrainer.util.Params;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/** End-to-end idempotency for proxied writes (bean 0o9b). When a client sends an
 * {@code Idempotency-Key} on a write (the strangler forwards it), a retry with
 * the same key returns the FIRST response instead of re-applying the mutation —
 * closing the double-apply window when a delivered write's response is lost (the
 * strangler returns 502 and a client auto-retries).
 *
 * Priority 5100 → runs AFTER the auth filter (default USER=5000) so the athlete
 * is resolved and the key can be tenant-scoped; the abort still short-circuits
 * the resource. Only 2xx responses are cached — a 5xx must stay retryable.
 *
 * Ceiling: two EXACTLY-simultaneous duplicates both miss the cache and both run
 * (no in-flight lock); the real case — a sequential retry after the first
 * completes — is covered. Job-based writes are additionally deduped per
 * (athlete, kind) by the job system. */
@Provider
@Priority(5100)
public class IdempotencyFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger LOG = Logger.getLogger(IdempotencyFilter.class);
    private static final String HEADER = "Idempotency-Key";
    /** Request property carrying the resolved cache key to the response filter. */
    private static final String KEY_PROP = "io.gamov.idempotency.key";
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");
    /** Response headers NOT replayed: Set-Cookie (never re-emit a stale auth
     * cookie from cache) and the two the container recomputes. */
    private static final Set<String> SKIP_HEADERS = Set.of("set-cookie", "content-length", "content-type");

    @Inject
    IdempotencyStore store;

    @Inject
    CurrentAthlete current;

    @Override
    public void filter(ContainerRequestContext req) {
        String key = cacheKey(req);
        if (key == null) {
            return;
        }
        var hit = store.find(key);
        if (hit.isPresent()) {
            IdempotencyStore.Entry e = hit.get();
            LOG.infof("Idempotency replay: %s %s -> cached %d", req.getMethod(), req.getUriInfo().getPath(), e.status());
            Response.ResponseBuilder rb = Response.status(e.status())
                    .entity(e.body())
                    .type(e.mediaType() != null ? e.mediaType() : MediaType.APPLICATION_JSON)
                    .header("Idempotency-Replayed", "true");
            if (e.headers() != null) {
                e.headers().forEach((name, values) -> values.forEach(v -> rb.header(name, v)));
            }
            req.abortWith(rb.build());
            return;
        }
        req.setProperty(KEY_PROP, key);   // first time — let the write run, cache on the way out
    }

    @Override
    public void filter(ContainerRequestContext req, ContainerResponseContext resp) {
        Object key = req.getProperty(KEY_PROP);
        if (key == null) {
            return;
        }
        int status = resp.getStatus();
        // Cache only successful, final outcomes — a 5xx should stay retryable, and
        // a 4xx is a deterministic client error that will recur anyway.
        if (status >= 200 && status < 300) {
            String mt = resp.getMediaType() != null ? resp.getMediaType().toString() : MediaType.APPLICATION_JSON;
            store.save((String) key,
                    new IdempotencyStore.Entry(status, resp.getEntity(), mt, snapshotHeaders(resp)));
        }
    }

    /** Response headers to replay on a retry (Location, ETag, custom X-… survive);
     * Set-Cookie and the container-recomputed content headers are excluded. */
    private static Map<String, List<Object>> snapshotHeaders(ContainerResponseContext resp) {
        Map<String, List<Object>> out = new LinkedHashMap<>();
        resp.getHeaders().forEach((name, values) -> {
            if (!SKIP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                out.put(name, new ArrayList<>(values));
            }
        });
        return out;
    }

    /** The cache key, or null when idempotency does not apply. Scoped by
     * (athlete, method, path, key) so the SAME client key reused on a different
     * endpoint is a miss (runs normally) rather than replaying the wrong
     * response. Skips async writes: those return a job-submit envelope and are
     * already deduped per (athlete, kind) by the job system (ADR 0029) — caching
     * the 'queued' envelope would defeat that and pin a retry to a stale job. */
    private String cacheKey(ContainerRequestContext req) {
        if (!WRITE_METHODS.contains(req.getMethod())) {
            return null;
        }
        String idem = req.getHeaderString(HEADER);
        if (idem == null || idem.isBlank()) {
            return null;
        }
        if (Params.boolOr(req.getUriInfo().getQueryParameters().getFirst("async"), false)) {
            return null;   // async → job dedup owns retry-safety, not the cache
        }
        Integer aid = current.idOrNull();
        if (aid == null) {
            return null;   // unauthenticated write: no tenant to scope the key to
        }
        return aid + ":" + req.getMethod() + ":" + req.getUriInfo().getPath() + ":" + idem.strip();
    }
}
