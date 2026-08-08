package io.gamov.irontrainer.admin;

import io.gamov.irontrainer.auth.SessionCookie;
import java.util.Map;

/**
 * The admin console gate (bean gfb3): a SHARED-PASSWORD session, deliberately
 * decoupled from any athlete account. On a correct password the server mints a
 * signed {@code admin_session} cookie using the same itsdangerous-compatible HMAC
 * signer as the athlete session — so an attacker can't forge it without the secret.
 * The payload just carries an {@code admin} marker; its mere presence in a validly
 * signed, unexpired cookie means "authenticated admin".
 */
public final class AdminSession {

    public static final String COOKIE = "admin_session";

    private AdminSession() {
    }

    /** Mint the admin_session cookie value (signed with the shared session secret). */
    public static String sign(String secret) {
        return SessionCookie.sign(Map.of("admin", true), secret);
    }

    /**
     * True when the Cookie header carries a validly-signed, unexpired admin_session.
     * Uses the LAST {@code admin_session=} occurrence (http.cookies semantics, matching
     * BearerAuthFilter). Any signature/expiry/secret problem → false (never throws).
     */
    public static boolean isValid(String cookieHeader, String secret) {
        if (cookieHeader == null || secret == null || secret.isBlank()) {
            return false;
        }
        String value = null;
        for (String part : cookieHeader.split(";")) {
            String p = part.trim();
            if (p.startsWith(COOKIE + "=")) {
                value = p.substring(COOKIE.length() + 1);
            }
        }
        if (value == null) {
            return false;
        }
        Map<String, Object> m = SessionCookie.read(value, secret);
        return m != null && m.containsKey("admin");
    }
}
