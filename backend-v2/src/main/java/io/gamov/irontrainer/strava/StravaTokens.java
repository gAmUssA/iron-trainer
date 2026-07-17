package io.gamov.irontrainer.strava;

import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.util.PyJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import java.time.Instant;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/** Strava OAuth token lifecycle — port of services.valid_access_token +
 * repo.save_tokens. Refreshes the access token when it's within 60s of expiry. */
@ApplicationScoped
public class StravaTokens {

    private static final Logger LOG = Logger.getLogger(StravaTokens.class);

    @RestClient
    StravaApi strava;

    @ConfigProperty(name = "strava.client-id")
    String clientId;

    @ConfigProperty(name = "strava.client-secret")
    String clientSecret;

    /** A valid access token, refreshing (and persisting) if expired. Raises a 409
     * (NotConnected) when the athlete has never connected Strava. */
    @Transactional
    public String validAccessToken(int aid) {
        Athlete a = Athlete.findById(aid);
        String refresh = a == null ? null : a.stravaRefreshToken;
        if (refresh == null || refresh.isEmpty()) {
            throw new WebApplicationException(
                    "Strava is not connected. Visit /api/strava/connect first.", 409);
        }
        long expiresAt = a.stravaTokenExpiresAt == null ? 0 : a.stravaTokenExpiresAt;
        if (expiresAt <= Instant.now().getEpochSecond() + 60) {
            LOG.infof("Strava token expired for athlete %d — refreshing.", aid);
            Map<String, Object> token = strava.token(clientId, clientSecret, "refresh_token", refresh);
            saveTokens(a, token);
            return (String) token.get("access_token");
        }
        return a.stravaAccessToken;
    }

    /** Persist the tokens (and athlete identity) from a Strava token response. */
    public void saveTokens(Athlete a, Map<String, Object> token) {
        Object athObj = token.get("athlete");
        if (athObj instanceof Map<?, ?> ath) {
            if (ath.get("id") instanceof Number sid) a.stravaAthleteId = sid.longValue();
            String name = join((String) ath.get("firstname"), (String) ath.get("lastname"));
            if (name != null) a.name = name;
        }
        a.stravaAccessToken = (String) token.get("access_token");
        a.stravaRefreshToken = (String) token.get("refresh_token");
        a.stravaTokenExpiresAt = token.get("expires_at") instanceof Number n ? n.longValue() : null;
        a.updatedAt = PyJson.utcNowIso();
    }

    private static String join(String first, String last) {
        StringBuilder sb = new StringBuilder();
        if (first != null && !first.isEmpty()) sb.append(first);
        if (last != null && !last.isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(last);
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
