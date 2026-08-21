package io.gamov.irontrainer.whoop;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import jakarta.enterprise.context.ApplicationScoped;
import io.gamov.irontrainer.util.SecureTokens;
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
     * one is rejected outright, so 24 bytes is sized well past that.
     *
     * <p>Via SecureTokens, which makes a fresh SecureRandom per call. A
     * {@code static final SecureRandom} is initialized at BUILD time and baked
     * into the native image heap with a cached seed, so every deployment of the
     * image would emit the same sequence of CSRF states. GraalVM rejects it
     * outright (UnsupportedFeatureException: "Detected an instance of
     * Random/SplittableRandom class in the image heap") — which is the build
     * catching a security bug, not being fussy. StravaOAuth.newState does the
     * same thing for the same reason. */
    public static String newState() {
        return SecureTokens.urlsafe(24);
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
