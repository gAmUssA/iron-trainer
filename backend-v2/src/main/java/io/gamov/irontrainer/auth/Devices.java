package io.gamov.irontrainer.auth;

import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.util.PyJson;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Device-token lifecycle — the native-app login primitives. Tokens are minted
 * here and returned in plaintext exactly once; only the SHA-256 hash is stored
 * (verified by BearerAuthFilter). Port of repo.create_pairing_code /
 * create_bearer_token / claim_pairing_code / bearer_token_name /
 * revoke_device_tokens. */
@ApplicationScoped
public class Devices {

    public static final String INGEST_TOKEN_NAME = "health-auto-export";
    private static final int PAIRING_TTL_S = 600;

    /** create_pairing_code: an 8-hex-char code, 10-min TTL. */
    @Transactional
    public Map<String, Object> createPairingCode(int aid, String name) {
        String code = tokenHex(4);
        long expiresAt = Instant.now().getEpochSecond() + PAIRING_TTL_S;
        DeviceToken t = new DeviceToken();
        t.athleteId = aid;
        t.name = name;
        t.pairingCode = code;
        t.pairingExpiresAt = expiresAt;
        t.createdAt = PyJson.utcNowIso();
        t.persist();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("expires_at", expiresAt);
        out.put("expires_in", PAIRING_TTL_S);
        return out;
    }

    /** create_bearer_token: mint a token directly (no pairing dance). */
    @Transactional
    public String createBearerToken(String name, int aid) {
        String token = tokenUrlsafe(32);
        DeviceToken t = new DeviceToken();
        t.athleteId = aid;
        t.name = name;
        t.tokenHash = BearerAuthFilter.sha256(token);
        t.createdAt = PyJson.utcNowIso();
        t.persist();
        return token;
    }

    /** claim_pairing_code: exchange a valid, unexpired, unclaimed code for a
     * bearer token → {token, athlete{name, strava_athlete_id}}; null if invalid. */
    @Transactional
    public Map<String, Object> claimPairingCode(String code, String deviceName) {
        long now = Instant.now().getEpochSecond();
        DeviceToken row = DeviceToken.find("pairingCode", code).firstResult();
        if (row == null || row.tokenHash != null) {
            return null;   // unknown or already claimed
        }
        if (row.pairingExpiresAt != null && row.pairingExpiresAt < now) {
            return null;   // expired
        }
        String token = tokenUrlsafe(32);
        row.tokenHash = BearerAuthFilter.sha256(token);
        row.pairingCode = null;
        row.pairingExpiresAt = null;
        if (deviceName != null && !deviceName.isEmpty()) {   // Python `if device_name:`
            row.name = deviceName;
        }
        row.lastUsedAt = PyJson.utcNowIso();
        Athlete a = Athlete.findById(row.athleteId);
        Map<String, Object> athlete = new LinkedHashMap<>();
        athlete.put("name", a == null ? null : a.name);
        athlete.put("strava_athlete_id", a == null ? null : a.stravaAthleteId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("athlete", athlete);
        return out;
    }

    /** bearer_token_name: the DeviceToken.name behind a presented token, or null. */
    @Transactional
    public String bearerTokenName(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        DeviceToken row = DeviceToken.find("tokenHash", BearerAuthFilter.sha256(token)).firstResult();
        return row == null ? null : row.name;
    }

    /** revoke_device_tokens: delete all of the athlete's device tokens. */
    @Transactional
    public int revokeTokens(int aid) {
        return (int) DeviceToken.delete("athleteId", aid);
    }

    /** secrets.token_urlsafe(nbytes) — urlsafe base64, no padding. A fresh
     * SecureRandom per call (minting is infrequent; a static instance would be
     * baked into the native-image heap, which GraalVM rejects). */
    private static String tokenUrlsafe(int nbytes) {
        byte[] b = new byte[nbytes];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** secrets.token_hex(nbytes) — 2 hex chars per byte. */
    private static String tokenHex(int nbytes) {
        byte[] b = new byte[nbytes];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
