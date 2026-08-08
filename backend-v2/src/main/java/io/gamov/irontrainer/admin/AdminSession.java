package io.gamov.irontrainer.admin;

import io.gamov.irontrainer.auth.SessionCookie;
import java.time.Instant;
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

    /** Admin session lifetime — 12h. Enforced SERVER-SIDE via the payload `exp`, not
     * just the browser Max-Age. (SessionCookie's own gate is 14 days, far too long for
     * an admin token, so we carry + check our own expiry — bean gfb3 review.) */
    public static final long TTL_SECONDS = 43_200L;

    private AdminSession() {
    }

    /** Mint the admin_session cookie value: signed marker + a 12h expiry. */
    public static String sign(String secret) {
        long exp = Instant.now().getEpochSecond() + TTL_SECONDS;
        return SessionCookie.sign(Map.of("admin", true, "exp", exp), secret);
    }

    /**
     * True when the Cookie header carries a validly-signed admin_session whose `exp`
     * is still in the future. Uses the LAST {@code admin_session=} occurrence
     * (http.cookies semantics, matching BearerAuthFilter). Any signature/expiry/secret
     * problem → false (never throws).
     */
    public static boolean isValid(String cookieHeader, String secret) {
        return isValid(cookieHeader, secret, Instant.now().getEpochSecond());
    }

    /** Testable overload with an injected clock. */
    static boolean isValid(String cookieHeader, String secret, long nowEpochSeconds) {
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
        if (m == null || !m.containsKey("admin")) {
            return false;
        }
        return m.get("exp") instanceof Number exp && nowEpochSeconds < exp.longValue();
    }
}
