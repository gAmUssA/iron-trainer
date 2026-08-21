package io.gamov.irontrainer.whoop;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** WHOOP OAuth URL construction and CSRF state (bean 4a6s).
 *
 * <p>Deliberately smaller than its Strava counterpart, because WHOOP is <b>not</b>
 * a login mechanism here. Strava's callback has to find-or-create an athlete,
 * apply an allowlist and mint a login session; WHOOP only ever attaches an
 * integration to an athlete who is already identified. No new users, no
 * allowlist, no session minting — just state verification and a token exchange.
 */
@ApplicationScoped
public class WhoopOAuth {

    private static final SecureRandom RANDOM = new SecureRandom();

    @ConfigProperty(name = "whoop.client-id")
    Optional<String> clientId;

    @ConfigProperty(name = "whoop.client-secret")
    Optional<String> clientSecret;

    @ConfigProperty(name = "whoop.redirect-uri")
    String redirectUri;

    public boolean configured() {
        return clientId.filter(s -> !s.isBlank()).isPresent()
                && clientSecret.filter(s -> !s.isBlank()).isPresent();
    }

    public String redirectUri() {
        return redirectUri;
    }

    /** WHOOP requires the state parameter to be at least 8 characters — a shorter
     * one is rejected outright, so this is sized well past that rather than
     * assuming a UUID-ish default would do. */
    public static String newState() {
        byte[] buf = new byte[24];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /** The consent-screen URL.
     *
     * <p>`offline` is what makes a refresh token come back at all — without it the
     * connection silently dies at the first access-token expiry, which is a bug
     * you would not notice until the next morning's sync. */
    public String authorizeUrl(String state) {
        return "https://api.prod.whoop.com/oauth/oauth2/auth"
                + "?client_id=" + enc(clientId.orElse(""))
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&scope=" + enc(WhoopTokens.SCOPE)
                + "&state=" + enc(state);
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }
}
