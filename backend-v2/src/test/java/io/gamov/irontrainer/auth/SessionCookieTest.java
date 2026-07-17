package io.gamov.irontrainer.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Byte-exact parity against a cookie signed by the real Python itsdangerous
 * 2.2.0 (the version FastAPI/Starlette use). The fixture below was produced by
 * a FixedSigner over {@code {"athlete_id": 7}} with secret "test-secret-key"
 * at a pinned epoch, so the age gate is deterministic:
 * <pre>
 *   base64.b64encode(json.dumps({"athlete_id": 7}).encode())  -> payload
 *   TimestampSigner("test-secret-key", get_timestamp()->1700000000).sign(payload)
 * </pre> */
class SessionCookieTest {

    private static final String SECRET = "test-secret-key";
    private static final long SIGNED_AT = 1_700_000_000L;
    // payload {"athlete_id": 7} . timestamp(1700000000) . HMAC-SHA1 signature
    private static final String COOKIE =
            "eyJhdGhsZXRlX2lkIjogN30=.ZVPxAA.d-cuM89lfIvJTyg5a8oKFq6JAQ4";

    @Test
    void verifiesPythonSignedCookie() {
        // Just after signing → within the 14-day window.
        Integer aid = SessionCookie.athleteId(COOKIE, SECRET, SIGNED_AT + 60);
        assertEquals(7, aid, "the athlete_id from a valid Python-signed session cookie");
    }

    @Test
    void acceptsAcrossTheFullWindow() {
        // One second inside the 14-day max_age boundary.
        assertEquals(7, SessionCookie.athleteId(COOKIE, SECRET,
                SIGNED_AT + SessionCookie.MAX_AGE_SECONDS - 1));
    }

    @Test
    void rejectsExpired() {
        // One second past max_age.
        assertNull(SessionCookie.athleteId(COOKIE, SECRET,
                SIGNED_AT + SessionCookie.MAX_AGE_SECONDS + 1));
    }

    @Test
    void rejectsFutureTimestamp() {
        // now before the signing time → negative age, rejected (itsdangerous parity).
        assertNull(SessionCookie.athleteId(COOKIE, SECRET, SIGNED_AT - 10));
    }

    @Test
    void rejectsTamperedSignature() {
        String tampered = COOKIE.substring(0, COOKIE.length() - 1)
                + (COOKIE.charAt(COOKIE.length() - 1) == 'A' ? 'B' : 'A');
        assertNull(SessionCookie.athleteId(tampered, SECRET, SIGNED_AT + 60));
    }

    @Test
    void rejectsWrongSecret() {
        assertNull(SessionCookie.athleteId(COOKIE, "not-the-secret", SIGNED_AT + 60));
    }

    @Test
    void rejectsMissingOrEmpty() {
        assertNull(SessionCookie.athleteId(null, SECRET, SIGNED_AT));
        assertNull(SessionCookie.athleteId(COOKIE, null, SIGNED_AT));
        assertNull(SessionCookie.athleteId(COOKIE, "", SIGNED_AT));
        assertNull(SessionCookie.athleteId("garbage", SECRET, SIGNED_AT));
        assertNull(SessionCookie.athleteId("only.two", SECRET, SIGNED_AT));
    }

    @Test
    void headerParseSurvivesEqualsAndDotsInValue() {
        String header = "foo=bar; session=" + COOKIE + "; other=baz";
        assertEquals(COOKIE, BearerAuthFilter.sessionCookieValue(header));
        assertNull(BearerAuthFilter.sessionCookieValue("foo=bar; other=baz"));
        assertNull(BearerAuthFilter.sessionCookieValue(null));
    }
}
