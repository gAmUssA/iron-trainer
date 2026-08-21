package io.gamov.irontrainer.whoop;

import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.util.PyJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/** WHOOP OAuth token lifecycle (bean 4a6s). Mirrors {@link
 * io.gamov.irontrainer.strava.StravaTokens} in shape, but the refresh rules are
 * stricter and the failure mode is worse, so the differences are deliberate.
 *
 * <h3>Why this is not just StravaTokens with different field names</h3>
 *
 * WHOOP refresh tokens are <b>single-use</b>: a successful refresh invalidates the
 * pair that produced it, and WHOOP documents that concurrent refresh requests
 * fail. Two consequences:
 *
 * <ol>
 *   <li><b>Losing the new refresh token locks the athlete out.</b> With Strava the
 *       old refresh token keeps working, so a crash between "refreshed" and
 *       "persisted" is survivable. Here it is not — the old token is already dead
 *       and the new one was never written, so the only recovery is reconnecting by
 *       hand. The persist therefore happens in the SAME transaction as the refresh,
 *       and nothing else is allowed between them.</li>
 *   <li><b>Two refreshes must never race.</b> {@code synchronized} on the refresh
 *       path serializes them within a process. That is enough here because the
 *       daily sync runs as a single JobRunner job and JobRunner already blocks a
 *       second job of the same kind. It would NOT be enough across multiple
 *       instances — see the note on that below rather than assuming this scales.</li>
 * </ol>
 */
@ApplicationScoped
public class WhoopTokens {

    private static final Logger LOG = Logger.getLogger(WhoopTokens.class);

    /** Refresh this many seconds before the token actually expires, so a long sync
     * cannot have a token die underneath it mid-page. */
    private static final long EXPIRY_MARGIN_S = 120;

    /** Scope must be re-sent on refresh; `offline` is what keeps the refresh token
     * alive at all. Dropping it silently downgrades the grant. */
    static final String SCOPE = "offline read:recovery read:cycles read:sleep";

    @RestClient
    WhoopApi whoop;

    // Optional for the same reason as Strava's: an unconfigured install (CI, a
    // self-hoster who has not connected WHOOP) must still boot. SmallRye maps an
    // empty env var to an absent Optional.
    @ConfigProperty(name = "whoop.client-id")
    Optional<String> clientId;

    @ConfigProperty(name = "whoop.client-secret")
    Optional<String> clientSecret;

    @ConfigProperty(name = "whoop.redirect-uri")
    String redirectUri;

    /** True when this deployment has WHOOP credentials at all — the UI uses it to
     * decide whether to offer a Connect button rather than letting the user click
     * into a guaranteed failure. */
    public boolean configured() {
        return clientId.filter(s -> !s.isBlank()).isPresent()
                && clientSecret.filter(s -> !s.isBlank()).isPresent();
    }

    /** A valid access token, refreshing and persisting if it is at or near expiry.
     * 409 when the athlete has never connected WHOOP, matching the Strava vertical.
     *
     * <p>synchronized: see the class docs — concurrent refreshes fail at WHOOP and
     * the loser is left holding a dead refresh token. */
    public synchronized String validAccessToken(int aid) {
        Athlete a = Athlete.findById(aid);
        String refresh = a == null ? null : a.whoopRefreshToken;
        if (refresh == null || refresh.isEmpty()) {
            throw new WebApplicationException(
                    "WHOOP is not connected. Visit /api/whoop/connect first.", 409);
        }
        long expiresAt = a.whoopTokenExpiresAt == null ? 0 : a.whoopTokenExpiresAt;
        if (expiresAt > Instant.now().getEpochSecond() + EXPIRY_MARGIN_S) {
            return a.whoopAccessToken;
        }
        LOG.infof("WHOOP token expiring for athlete %d — refreshing.", aid);
        return refreshAndPersist(aid, refresh);
    }

    /** The refresh + persist pair, in one transaction. Split out so the
     * transactional boundary is exactly around "get new tokens, write new tokens"
     * — a rollback here must not leave the athlete holding a spent refresh token. */
    @Transactional
    String refreshAndPersist(int aid, String refresh) {
        Map<String, Object> token;
        try {
            token = whoop.refresh(clientId.orElse(""), clientSecret.orElse(""),
                    "refresh_token", refresh, SCOPE);
        } catch (RuntimeException e) {
            // A rejected refresh is usually terminal: the token was already spent,
            // revoked at WHOOP, or expired from disuse. Say so plainly, because the
            // only fix is a human reconnecting — retrying will not help.
            LOG.errorf(e, "WHOOP refresh failed for athlete %d — reconnect required.", aid);
            throw new WebApplicationException(
                    "WHOOP sign-in has expired. Reconnect WHOOP in Settings.", 409);
        }
        Athlete a = Athlete.findById(aid);
        saveTokens(a, token);
        return (String) token.get("access_token");
    }

    /** Persist a WHOOP token response onto the athlete.
     *
     * <p>WHOOP returns {@code expires_in} (seconds from now), unlike Strava's
     * absolute {@code expires_at}; converting here keeps the stored column an
     * absolute epoch second in both verticals. */
    public void saveTokens(Athlete a, Map<String, Object> token) {
        if (a == null || token == null) {
            return;
        }
        a.whoopAccessToken = (String) token.get("access_token");
        // Only overwrite the refresh token when a new one came back. WHOOP always
        // rotates, but a null here would wipe a working token and lock the athlete
        // out — the one destructive outcome this class exists to avoid.
        String newRefresh = (String) token.get("refresh_token");
        if (newRefresh != null && !newRefresh.isBlank()) {
            a.whoopRefreshToken = newRefresh;
        }
        if (token.get("expires_in") instanceof Number n) {
            a.whoopTokenExpiresAt = Instant.now().getEpochSecond() + n.longValue();
        }
        a.updatedAt = PyJson.utcNowIso();
    }

    /** Exchange an authorization code for the first token pair. The redirect_uri
     * must match the one sent to the authorize endpoint AND the one registered in
     * the WHOOP dashboard — WHOOP compares all three. */
    public Map<String, Object> exchange(String code) {
        return whoop.exchangeCode(clientId.orElse(""), clientSecret.orElse(""),
                code, "authorization_code", redirectUri);
    }

    /** Who this token belongs to. Used once at connect to stamp whoop_user_id. */
    public Map<String, Object> profile(String accessToken) {
        return whoop.profile("Bearer " + accessToken);
    }

    /** Forget the connection locally (disconnect). WHOOP has no documented
     * revoke endpoint in v2, so this is local-only and the user should also revoke
     * in their WHOOP account settings — the UI says so rather than implying we
     * severed it at their end. */
    @Transactional
    public void disconnect(int aid) {
        Athlete a = Athlete.findById(aid);
        if (a == null) {
            return;
        }
        a.whoopAccessToken = null;
        a.whoopRefreshToken = null;
        a.whoopTokenExpiresAt = null;
        a.updatedAt = PyJson.utcNowIso();
        LOG.infof("WHOOP disconnected locally for athlete %d.", aid);
    }
}
