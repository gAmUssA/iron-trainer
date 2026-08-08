package io.gamov.irontrainer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Ingest source detection (bean j05e) — pure logic, no Quarkus/Docker. */
class HealthSourceDetectTest {

    @Test
    void headerWins() {
        assertEquals("native", HealthResource.detectSource("native", "whatever"));
        assertEquals("hae", HealthResource.detectSource("hae", null));
        assertEquals("hae", HealthResource.detectSource("Health Auto Export", null));
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
