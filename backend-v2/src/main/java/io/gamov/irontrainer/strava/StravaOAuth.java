package io.gamov.irontrainer.strava;

import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/** Strava OAuth — authorize-URL builder, code exchange, deauthorize, and the
 * login policy config (allowlist, auth-required, SPA origin) for the connect +
 * callback verticals (bean xtre). The HTTP calls go through StravaApi; the
 * session-cookie minting through SessionCookie. Port of app/strava + the
 * strava_router config reads. */
@ApplicationScoped
public class StravaOAuth {

    static final String AUTHORIZE_URL = "https://www.strava.com/oauth/authorize";
    static final String SCOPE = "read,activity:read_all";

    @RestClient
    StravaApi api;

    // Optional: the ${VAR:} defaults resolve to empty, which SmallRye's String
    // converter treats as null (would fail a required @ConfigProperty at boot).
    @ConfigProperty(name = "strava.client-id")
    Optional<String> clientId;

    @ConfigProperty(name = "strava.client-secret")
    Optional<String> clientSecret;

    @ConfigProperty(name = "strava.redirect-uri")
    String redirectUri;

    // Deployment mode (settings.auth_required): gates login/allowlist. Default
    // false = local single-user mode (matches FastAPI + BearerAuthFilter).
    @ConfigProperty(name = "irontrainer.auth-required")
    boolean authRequired;

    // settings.allowed_strava_ids: comma-separated Strava athlete ids that may
    // log in (empty = allow all). Optional so an unset env doesn't fail boot.
    @ConfigProperty(name = "irontrainer.allowed-strava-ids")
    Optional<String> allowedStravaIds;

    // settings.cors_origin_list[0]: where OAuth outcomes redirect (the SPA). Same
    // CORS_ORIGINS env FastAPI reads; comma-separated, first non-blank wins.
    @ConfigProperty(name = "strava.frontend-origin")
    Optional<String> frontendOrigins;

    /** strava_configured: both client id and secret are set (non-blank). */
    public boolean configured() {
        return clientId.filter(s -> !s.isBlank()).isPresent()
                && clientSecret.filter(s -> !s.isBlank()).isPresent();
    }

    /** settings.auth_required — deployment mode (login + allowlist enforced). */
    public boolean authRequired() {
        return authRequired;
    }

    /** exchange_code: swap the authorization code for tokens (login). Propagates
     * the REST-client exception on a non-2xx so callback can map it to a
     * strava_error=exchange_failed redirect. */
    public Map<String, Object> exchangeCode(String code) {
        return api.exchangeCode(clientId.orElse(""), clientSecret.orElse(""), code, "authorization_code");
    }

    /** deauthorize: revoke the app's access for this athlete at Strava. */
    public void deauthorize(String accessToken) {
        api.deauthorize("Bearer " + accessToken);
    }

    /** is_allowed: whether this Strava athlete may log in (empty allowlist = all). */
    public boolean isAllowed(long stravaAthleteId) {
        Set<BigInteger> allow = allowedIds();
        return allow.isEmpty() || allow.contains(BigInteger.valueOf(stravaAthleteId));
    }

    /** allowed_strava_id_set: comma-split, keep the all-digit tokens (parity with
     * the Python `tok.isdigit()` filter — non-numeric junk is silently dropped).
     * BigInteger (not long) matches Python's arbitrary-precision `int(tok)`: an
     * over-long id stays in the set (never matches a real athlete) instead of
     * throwing and 500-ing every login, and leading zeros normalize the same. */
    private Set<BigInteger> allowedIds() {
        Set<BigInteger> out = new LinkedHashSet<>();
        for (String tok : allowedStravaIds.orElse("").split(",")) {
            String t = tok.strip();
            if (!t.isEmpty() && t.chars().allMatch(Character::isDigit)) {
                out.add(new BigInteger(t));
            }
        }
        return out;
    }

    /** cors_origin_list[0]: the SPA origin OAuth outcomes redirect back to, or ""
     * when none is configured (then _redirect builds a root-relative "/?..."). */
    public String frontendOrigin() {
        for (String o : frontendOrigins.orElse("").split(",")) {
            String s = o.strip();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return "";
    }

    /** secrets.token_urlsafe(16) — the CSRF oauth_state. */
    public static String newState() {
        return io.gamov.irontrainer.util.SecureTokens.urlsafe(16);
    }

    /** authorize_url: the Strava consent URL. Param order matches Python
     * urlencode(dict): client_id, redirect_uri, response_type, scope,
     * approval_prompt, state (form-encoded values). */
    public String authorizeUrl(String state) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", clientId.orElse(""));
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
