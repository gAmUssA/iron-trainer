package io.gamov.irontrainer.strava;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The external device-name fetch (over the WireMock Strava). Mirrors the fetch
 * loop in services.deduplicate: cap, 429→break, other-error→skip, and a
 * successful-but-deviceless fetch still counts. */
@QuarkusTest
@QuarkusTestResource(WireMockStrava.class)
class DedupServiceTest {

    @Inject
    DedupService dedupService;

    @Test
    void fetchesDeviceNamesUpToTheCap() {
        DedupService.DeviceFetch df =
                dedupService.resolveMissingDeviceNames(List.of(501L, 502L), "Bearer T", 200);
        assertEquals(2, df.fetched());
        assertEquals("Garmin Edge 530", df.devices().get(501L));
        assertEquals("Apple Watch", df.devices().get(502L));
    }

    @Test
    void capBoundsTheNumberOfFetches() {
        DedupService.DeviceFetch df =
                dedupService.resolveMissingDeviceNames(List.of(501L, 502L), "Bearer T", 1);
        assertEquals(1, df.fetched());
        assertTrue(df.devices().containsKey(501L));
        assertTrue(!df.devices().containsKey(502L), "second id not fetched under cap=1");
    }

    @Test
    void rateLimitBreaksTheLoop() {
        // 555 → 429 first → break before 501 is ever fetched.
        DedupService.DeviceFetch df =
                dedupService.resolveMissingDeviceNames(List.of(555L, 501L), "Bearer T", 200);
        assertEquals(0, df.fetched());
        assertTrue(df.devices().isEmpty());
    }

    @Test
    void otherErrorsSkipButContinue() {
        // 556 → 500 (skip), 501 → ok. One successful fetch.
        DedupService.DeviceFetch df =
                dedupService.resolveMissingDeviceNames(List.of(556L, 501L), "Bearer T", 200);
        assertEquals(1, df.fetched());
        assertEquals("Garmin Edge 530", df.devices().get(501L));
        assertNull(df.devices().get(556L));
    }

    @Test
    void noAuthOrEmptyNeedFetchesNothing() {
        assertEquals(0, dedupService.resolveMissingDeviceNames(List.of(501L), null, 200).fetched());
        assertEquals(0, dedupService.resolveMissingDeviceNames(List.of(), "Bearer T", 200).fetched());
    }
}
