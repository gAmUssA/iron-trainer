package io.gamov.irontrainer.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Duration + percentile helpers for admin job latency (bean og06) — pure, no Docker. */
class AdminHealthDurationTest {

    @Test
    void durationMs() {
        assertEquals(5000L, AdminHealthResource.durationMs(
                "2026-08-09T10:00:00.000000+00:00", "2026-08-09T10:00:05.000000+00:00"));
        assertNull(AdminHealthResource.durationMs(   // negative span → null
                "2026-08-09T10:00:05.000000+00:00", "2026-08-09T10:00:00.000000+00:00"));
        assertNull(AdminHealthResource.durationMs("garbage", "2026-08-09T10:00:05+00:00"));
        assertNull(AdminHealthResource.durationMs(null, null));
    }

    @Test
    void percentileNearestRank() {
        List<Long> s = List.of(100L, 200L, 300L, 400L); // pre-sorted
        assertEquals(200L, AdminHealthResource.percentile(s, 50));   // ceil(.5*4)-1 = 1
        assertEquals(400L, AdminHealthResource.percentile(s, 95));   // ceil(.95*4)-1 = 3
        assertEquals(100L, AdminHealthResource.percentile(s, 1));    // floor to first
        assertEquals(50L, AdminHealthResource.percentile(List.of(50L), 50));
        assertNull(AdminHealthResource.percentile(List.of(), 50));
        assertNull(AdminHealthResource.percentile(null, 95));
    }
}
