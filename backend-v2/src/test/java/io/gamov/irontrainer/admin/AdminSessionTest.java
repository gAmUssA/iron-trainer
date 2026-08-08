package io.gamov.irontrainer.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for the signed admin_session gate (bean gfb3). */
class AdminSessionTest {

    private static final String SECRET = "test-secret-key";

    private static String header(String value) {
        return AdminSession.COOKIE + "=" + value;
    }

    @Test
    void signedCookieValidatesRoundTrip() {
        assertTrue(AdminSession.isValid(header(AdminSession.sign(SECRET)), SECRET));
    }

    @Test
    void wrongSecretOrTamperedIsInvalid() {
        String v = AdminSession.sign(SECRET);
        assertFalse(AdminSession.isValid(header(v), "different-secret"), "wrong secret");
        assertFalse(AdminSession.isValid(header(v + "x"), SECRET), "tampered signature");
    }

    @Test
    void absentOrUnconfiguredIsInvalid() {
        assertFalse(AdminSession.isValid(null, SECRET));
        assertFalse(AdminSession.isValid("session=somethingelse", SECRET), "different cookie name");
        assertFalse(AdminSession.isValid(header(AdminSession.sign(SECRET)), null), "no secret");
        assertFalse(AdminSession.isValid(header(AdminSession.sign(SECRET)), ""), "blank secret");
    }

    @Test
    void lastOccurrenceWins() {
        String good = AdminSession.sign(SECRET);
        assertTrue(AdminSession.isValid("admin_session=junk; admin_session=" + good, SECRET));
    }
}
