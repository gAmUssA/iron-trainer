package io.gamov.irontrainer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Ingest source detection (bean j05e) — pure logic, no Quarkus/Docker. */
class HealthSourceDetectTest {

    @Test
    void headerExactMatchWins() {
        assertEquals("native", HealthResource.detectSource("native", "whatever"));
        assertEquals("hae", HealthResource.detectSource("hae", null));
        assertEquals("native", HealthResource.detectSource(" NATIVE ", null)); // trimmed + case-insensitive
    }

    @Test
    void substringHeaderIsNotAccepted() {
        // "not-native" / "some-hae-client" must NOT classify as native/hae.
        assertEquals("unknown", HealthResource.detectSource("not-native", null));
        assertEquals("unknown", HealthResource.detectSource("some-hae-client", null));
        assertEquals("unknown", HealthResource.detectSource("Health Auto Export", null)); // header must be exactly "hae"
    }

    @Test
    void userAgentFallback() {
        assertEquals("hae", HealthResource.detectSource(null, "Health Auto Export/7.1 iOS"));
        assertEquals("hae", HealthResource.detectSource("", "health auto export"));
    }

    @Test
    void unknownByDefault() {
        assertEquals("unknown", HealthResource.detectSource(null, null));
        assertEquals("unknown", HealthResource.detectSource("", "curl/8.0"));
        assertEquals("unknown", HealthResource.detectSource("  ", "Mozilla/5.0"));
    }
}
