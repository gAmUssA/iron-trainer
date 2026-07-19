package io.gamov.irontrainer.strava;

import jakarta.enterprise.context.ApplicationScoped;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Strava OAuth — authorize-URL builder + config (bean xtre). The token
 * exchange lives on StravaApi; the session-cookie minting on SessionCookie. Port
 * of app/strava.authorize_url + the connect/callback config. */
@ApplicationScoped
public class StravaOAuth {

    static final String AUTHORIZE_URL = "https://www.strava.com/oauth/authorize";
    static final String SCOPE = "read,activity:read_all";

    @ConfigProperty(name = "strava.client-id")
    String clientId;

    @ConfigProperty(name = "strava.client-secret")
    String clientSecret;

    @ConfigProperty(name = "strava.redirect-uri")
    String redirectUri;

    /** strava_configured: both client id and secret are set. */
    public boolean configured() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    /** secrets.token_urlsafe(16) — 16 random bytes, urlsafe base64, no padding.
     * A fresh SecureRandom per call (OAuth connect is infrequent): a static
     * instance would be baked into the native image heap with a cached seed,
     * which GraalVM rejects (and would be insecure). */
    public static String newState() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** authorize_url: the Strava consent URL. Param order matches Python
     * urlencode(dict): client_id, redirect_uri, response_type, scope,
     * approval_prompt, state (form-encoded values). */
    public String authorizeUrl(String state) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri);
        params.put("response_type", "code");
        params.put("scope", SCOPE);
        params.put("approval_prompt", "auto");
        params.put("state", state);
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (q.length() > 0) {
                q.append('&');
            }
            q.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return AUTHORIZE_URL + "?" + q;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
