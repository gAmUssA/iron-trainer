package io.gamov.irontrainer.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Unit tests for the canonical-host redirect decision (bean 3e6w). */
class CanonicalHostRouteTest {

    @Test
    void wwwRedirectsToApexPreservingPathAndQuery() {
        assertEquals("https://irontrainer.app/api/status?x=1",
                CanonicalHostRoute.redirectTarget("irontrainer.app", "www.irontrainer.app", "/api/status?x=1"));
    }

    @Test
    void apexIsNotRedirected() {
        assertNull(CanonicalHostRoute.redirectTarget("irontrainer.app", "irontrainer.app", "/"));
    }

    @Test
    void railwayHealthCheckHostIsNotRedirected() {
        assertNull(CanonicalHostRoute.redirectTarget(
                "irontrainer.app", "iron-trainer.up.railway.app", "/q/health"));
    }

    @Test
    void hostPortIsStrippedAndMatchIsCaseInsensitive() {
        assertEquals("https://irontrainer.app/",
                CanonicalHostRoute.redirectTarget("irontrainer.app", "WWW.IronTrainer.App:443", "/"));
    }

    @Test
    void noCanonicalConfiguredMeansNoRedirect() {
        assertNull(CanonicalHostRoute.redirectTarget(null, "www.irontrainer.app", "/"));
        assertNull(CanonicalHostRoute.redirectTarget("", "www.irontrainer.app", "/"));
    }

    @Test
    void missingHostHeaderMeansNoRedirect() {
        assertNull(CanonicalHostRoute.redirectTarget("irontrainer.app", null, "/"));
    }
}
