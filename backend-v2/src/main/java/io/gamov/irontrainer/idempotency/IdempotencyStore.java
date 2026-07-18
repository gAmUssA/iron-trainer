package io.gamov.irontrainer.idempotency;

import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.cache.CaffeineCache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Stores completed write responses keyed by (athlete, Idempotency-Key) so a
 * retried write returns the SAME response instead of re-applying the mutation.
 *
 * Backed by Quarkus Cache (Caffeine, in-memory, bounded + TTL — see
 * application.properties). This class is the seam: it hides the cache behind a
 * find/save API so the backing store can later be swapped for a persistent one
 * (e.g. quarkus-redis-cache) by changing the extension + config, with NO change
 * here or in the filter. Ceiling of the in-memory backing: entries are per
 * instance and lost on restart, so a retry that spans a restart could re-apply —
 * acceptable because client 5xx-retries are near-immediate (bean 0o9b). */
@ApplicationScoped
public class IdempotencyStore {

    /** A completed write's response, replayed verbatim on a retry. `body` is the
     * resource's return object (re-serialized identically on replay). */
    public record Entry(int status, Object body, String mediaType) {}

    @Inject
    @CacheName("idempotency")
    Cache cache;

    public Optional<Entry> find(String key) {
        CompletableFuture<Object> f = cache.as(CaffeineCache.class).getIfPresent(key);
        if (f == null) {
            return Optional.empty();
        }
        // Only replay a value that's already resolved (a completed write); never
        // block the request thread waiting on an in-flight compute.
        Object v = f.getNow(null);
        return (v instanceof Entry e) ? Optional.of(e) : Optional.empty();
    }

    public void save(String key, Entry entry) {
        cache.as(CaffeineCache.class).put(key, CompletableFuture.completedFuture(entry));
    }
}
