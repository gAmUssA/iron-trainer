package io.gamov.irontrainer.app;

import io.quarkus.vertx.http.runtime.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Cache policy for the SPA that backend-v2 now serves. Quarkus' static handler
 * sends no Cache-Control at all, which is safe for index.html (browsers can't
 * hard-cache it without validators) but wasteful for the content-hashed assets
 * under /assets/* — they'd be refetched on every load. Vite fingerprints those
 * filenames, so they're immutable and safe to cache for a year; index.html is
 * the entry document and must stay fresh so new asset hashes are always picked
 * up after a redeploy.
 *
 * Registered via the core quarkus-vertx-http Filters hook (no extra extension),
 * so it also covers responses the static handler produces (which never pass
 * through JAX-RS). API paths (/api/*, /q/*) are left untouched.
 */
@ApplicationScoped
public class StaticCacheHeaders {

    void install(@Observes Filters filters) {
        filters.register(rc -> {
            rc.addHeadersEndHandler(v -> {
                String path = rc.normalizedPath();
                if (path.startsWith("/assets/")) {
                    rc.response().headers().set("Cache-Control", "public, max-age=31536000, immutable");
                } else if (path.equals("/") || path.equals("/index.html")) {
                    rc.response().headers().set("Cache-Control", "no-cache");
                }
            });
            rc.next();
        }, 10);
    }
}
