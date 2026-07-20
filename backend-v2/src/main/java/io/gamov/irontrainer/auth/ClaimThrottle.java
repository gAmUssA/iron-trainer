package io.gamov.irontrainer.auth;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Brute-force friction for the unauthenticated /device/claim endpoint. Pairing
 * codes are 32-bit with a 10-min TTL, so guessing needs millions of attempts —
 * deny that rate outright, keyed per client so one bad actor can't lock out
 * everyone. In-process (single-instance deploy); failures only. Port of
 * auth_router's _claim_throttled. Synchronized because Quarkus is multi-threaded
 * (FastAPI's single worker relied on the GIL). */
@ApplicationScoped
public class ClaimThrottle {

    private static final double WINDOW_S = 60.0;
    private static final int MAX_FAILURES = 10;

    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    public synchronized boolean throttled(String client) {
        long now = System.nanoTime();
        Deque<Long> q = failures.computeIfAbsent(client, k -> new ArrayDeque<>());
        while (!q.isEmpty() && (now - q.peekFirst()) / 1e9 > WINDOW_S) {
            q.pollFirst();
        }
        // Drop other clients' empty queues so the map can't grow unbounded.
        for (Iterator<Map.Entry<String, Deque<Long>>> it = failures.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Deque<Long>> e = it.next();
            if (e.getValue().isEmpty() && !e.getKey().equals(client)) {
                it.remove();
            }
        }
        return q.size() >= MAX_FAILURES;
    }

    public synchronized void recordFailure(String client) {
        failures.computeIfAbsent(client, k -> new ArrayDeque<>()).addLast(System.nanoTime());
    }

    public synchronized void clear(String client) {
        failures.remove(client);
    }
}
