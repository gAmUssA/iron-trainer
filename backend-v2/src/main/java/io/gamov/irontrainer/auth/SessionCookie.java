package io.gamov.irontrainer.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gamov.irontrainer.util.PyJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Byte-exact verifier for Starlette {@code SessionMiddleware} cookies —
 * itsdangerous 2.x {@code TimestampSigner}: HMAC-SHA1 over
 * {@code payload "." timestamp}, django-concat key derivation, salt
 * {@code "itsdangerous.Signer"}. Mirrors FastAPI's session auth so web
 * (cookie-only) clients resolve to an athlete on backend-v2.
 *
 * <p>backend-v2 only VERIFIES: minting stays on the FastAPI Strava OAuth
 * callback for the duration of the strangle window (see ADR / bean 4quc), so
 * existing browser sessions keep working with no re-login and no format change.
 *
 * <p>Cookie value layout ({@code '.'}-separated):
 * <pre>
 *   payload   = base64-STANDARD(json.dumps(session))   // with '=' padding
 *   timestamp = base64-urlsafe-nopad(minimal-big-endian(unix seconds))
 *   signature = base64-urlsafe-nopad(HMAC-SHA1(key, payload + "." + timestamp))
 *   key       = SHA1("itsdangerous.Signer" + "signer" + secret)   // 20 bytes
 * </pre> */
public final class SessionCookie {

    /** Starlette SessionMiddleware default max_age (14 days). */
    public static final long MAX_AGE_SECONDS = 1_209_600L;

    // django-concat: SHA1( salt + b"signer" + secret ), salt = b"itsdangerous.Signer".
    private static final byte[] DERIVE_PREFIX =
            "itsdangerous.Signersigner".getBytes(StandardCharsets.UTF_8);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SessionCookie() {}

    /** Resolve the athlete from a {@code session} cookie value, using the
     * current wall clock for the age gate. */
    public static Integer athleteId(String cookieValue, String secret) {
        return athleteId(cookieValue, secret, Instant.now().getEpochSecond());
    }

    /** Resolve the athlete from a {@code session} cookie value.
     * @return the {@code athlete_id}, or {@code null} when the cookie is
     *         absent, malformed, tampered, expired, or carries no athlete. */
    public static Integer athleteId(String cookieValue, String secret, long nowEpochSeconds) {
        Map<String, Object> session = read(cookieValue, secret, nowEpochSeconds);
        if (session == null) {
            return null;
        }
        Object aid = session.get("athlete_id");
        // Only an athlete_id that fits a signed 32-bit int (as before the refactor's
        // canConvertToInt guard) — never silently truncate a huge value into a
        // different athlete's id.
        if (aid instanceof Number n) {
            long v = n.longValue();
            if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE && (double) v == n.doubleValue()) {
                return (int) v;
            }
        }
        return null;
    }

    /** Verify a {@code session} cookie and return its full decoded contents
     * (e.g. for {@code oauth_state} in the Strava callback), or {@code null} when
     * absent/malformed/tampered/expired. */
    public static Map<String, Object> read(String cookieValue, String secret) {
        return read(cookieValue, secret, Instant.now().getEpochSecond());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(String cookieValue, String secret, long nowEpochSeconds) {
        if (cookieValue == null || secret == null || secret.isEmpty()) {
            return null;
        }
        int sigDot = cookieValue.lastIndexOf('.');
        if (sigDot <= 0) {
            return null;
        }
        String signed = cookieValue.substring(0, sigDot);   // payload + "." + timestamp
        String sigPart = cookieValue.substring(sigDot + 1);
        int tsDot = signed.lastIndexOf('.');
        if (tsDot <= 0) {
            return null;
        }
        String payloadPart = signed.substring(0, tsDot);
        String tsPart = signed.substring(tsDot + 1);

        // 1. Signature: HMAC-SHA1 over "payload.timestamp", constant-time compare.
        byte[] expected = hmacSha1(deriveKey(secret), signed.getBytes(StandardCharsets.UTF_8));
        byte[] actual = urlsafeDecode(sigPart);
        if (actual == null || !MessageDigest.isEqual(expected, actual)) {
            return null;
        }

        // 2. Age gate: 0 <= now - ts <= MAX_AGE_SECONDS (age<0 rejects a
        // future-stamped cookie, as itsdangerous 2.x SignatureExpired).
        byte[] tsBytes = urlsafeDecode(tsPart);
        if (tsBytes == null || tsBytes.length == 0 || tsBytes.length > 8) {
            return null;
        }
        long ts = 0;
        for (byte b : tsBytes) {
            ts = (ts << 8) | (b & 0xFF);
        }
        long age = nowEpochSeconds - ts;
        if (age < 0 || age > MAX_AGE_SECONDS) {
            return null;
        }

        // 3. Payload: STANDARD base64 → JSON → session map.
        try {
            byte[] json = Base64.getDecoder().decode(payloadPart);
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** MINT a Starlette {@code SessionMiddleware} cookie value from a session map
     * — the exact inverse of {@link #read}, byte-identical to Python
     * itsdangerous/Starlette. Used by the Strava OAuth connect/callback
     * (oauth_state / athlete_id login). Uses {@link PyJson#dumps} (", "/": "
     * spacing) so the base64 payload matches Starlette's {@code json.dumps}. */
    public static String sign(Map<String, Object> session, String secret) {
        return sign(session, secret, Instant.now().getEpochSecond());
    }

    static String sign(Map<String, Object> session, String secret, long nowEpochSeconds) {
        byte[] json = PyJson.dumps(session).getBytes(StandardCharsets.UTF_8);
        String payload = Base64.getEncoder().encodeToString(json);   // STANDARD base64, padded
        String tsPart = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(minimalBigEndian(nowEpochSeconds));
        String signed = payload + "." + tsPart;
        byte[] sig = hmacSha1(deriveKey(secret), signed.getBytes(StandardCharsets.UTF_8));
        return signed + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
    }

    /** itsdangerous int_to_bytes: minimal big-endian ((bit_length+7)/8 bytes). */
    private static byte[] minimalBigEndian(long v) {
        int numBytes = v == 0 ? 1 : (64 - Long.numberOfLeadingZeros(v) + 7) / 8;
        byte[] out = new byte[numBytes];
        for (int i = numBytes - 1; i >= 0; i--) {
            out[i] = (byte) (v & 0xFF);
            v >>>= 8;
        }
        return out;
    }

    private static byte[] deriveKey(String secret) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(DERIVE_PREFIX);
            md.update(secret.getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmacSha1(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** itsdangerous base64_decode: urlsafe alphabet, no padding. Java's URL
     * decoder accepts unpadded input directly. */
    private static byte[] urlsafeDecode(String s) {
        try {
            return Base64.getUrlDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
