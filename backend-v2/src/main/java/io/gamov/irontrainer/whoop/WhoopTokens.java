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
 *       "persisted" is survivable. Here it is not.
 *
 *       <p>This is <b>not solvable by a transaction</b>, and an earlier revision of
 *       this class wrongly implied it was. WHOOP invalidating the old token is an
 *       external side effect; JTA cannot roll it back. Wrapping the HTTP call in the
 *       transaction only widens the window in which a timeout can strand us. So the
 *       call happens OUTSIDE any transaction and the response is persisted in the
 *       shortest possible one. The residual risk — process death in the millisecond
 *       between response and commit — is real, unavoidable, and recovered by the
 *       athlete reconnecting; {@link #validAccessToken} surfaces that as a 409 with
 *       instructions rather than an opaque failure.</li>
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
     * alive at all. Dropping it silently downgrades the grant.
     *
     * <p>Deliberately WITHOUT read:profile. An earlier revision called
     * /v2/user/profile/basic to stamp whoop_user_id, which needs that scope — it
     * would have 403'd and been swallowed by a non-fatal catch, silently defeating
     * the cross-member check it existed for. The cycle records already carry
     * user_id, so the id comes from data we fetch anyway and the extra scope (plus
     * a dashboard edit and a re-consent) is unnecessary. */
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
        Athlete a = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                .call(() -> Athlete.<Athlete>findById(aid));
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

    /** HTTP first, OUTSIDE any transaction; then the shortest possible durable
     * write. See the class docs for why the reverse — call inside a transaction —
     * buys nothing and costs a wider failure window. */
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
            // Record it so the UI can say so. The refresh token is deliberately
            // left in place: this catch also sees transient WHOOP 5xx and network
            // faults, and wiping a working credential over one of those would cost
            // a manual reconnect for nothing. A later success clears the flag.
            io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
                Athlete a = Athlete.findById(aid);
                if (a != null) {
                    a.whoopReconnectRequired = true;
                }
            });
            throw new WebApplicationException(
                    "WHOOP sign-in has expired. Reconnect WHOOP in Settings.", 409);
        }
        // From here the OLD refresh token is already dead at WHOOP. Persist
        // immediately and do nothing else first.
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            Athlete a = Athlete.findById(aid);
            saveTokens(a, token);
        });
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
        // Any successful exchange or refresh means the connection is alive again.
        a.whoopReconnectRequired = null;
        a.updatedAt = PyJson.utcNowIso();
    }

    /** Exchange an authorization code for the first token pair. The redirect_uri
     * must match the one sent to the authorize endpoint AND the one registered in
     * the WHOOP dashboard — WHOOP compares all three. */
    public Map<String, Object> exchange(String code) {
        return whoop.exchangeCode(clientId.orElse(""), clientSecret.orElse(""),
                code, "authorization_code", redirectUri);
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
        // Not "reconnect required" — the athlete asked for this.
        a.whoopReconnectRequired = null;
        a.updatedAt = PyJson.utcNowIso();
        LOG.infof("WHOOP disconnected locally for athlete %d.", aid);
    }
}
