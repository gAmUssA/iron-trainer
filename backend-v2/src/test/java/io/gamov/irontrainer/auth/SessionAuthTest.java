package io.gamov.irontrainer.auth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.gamov.irontrainer.athlete.Athlete;
import io.gamov.irontrainer.metrics.MetricDaily;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** End-to-end proof that a web (cookie-only) request resolves the athlete via
 * the itsdangerous session cookie, and that a Bearer token still wins over a
 * cookie. The cookie is signed live (valid at {@code now}) using the same
 * algorithm FastAPI/Starlette use, against the test-profile secret
 * ({@code %test.irontrainer.session-secret=test-secret-key}).
 *
 * <p>Scope: this calls backend-v2 <em>directly</em> (RestAssured), verifying the
 * filter resolves a cookie once it arrives. Delivering the Cookie header over
 * the strangler proxy (currently bearer-only, GET-only) is bean hy6c — until
 * then this capability is staged, not on the live proxied path. */
@QuarkusTest
class SessionAuthTest {

    private static final String SECRET = "test-secret-key";

    /** Sign a Starlette-compatible session cookie for {@code {"athlete_id": aid}}. */
    private static String signSession(int aid, long ts) {
        try {
            byte[] payload = Base64.getEncoder().encode(
                    ("{\"athlete_id\": " + aid + "}").getBytes(StandardCharsets.UTF_8));
            String tsSeg = urlNoPad(minimalBigEndian(ts));
            byte[] toSign = concat(payload, ".".getBytes(StandardCharsets.US_ASCII),
                    tsSeg.getBytes(StandardCharsets.US_ASCII));
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update("itsdangerous.Signersigner".getBytes(StandardCharsets.UTF_8));
            sha1.update(SECRET.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(sha1.digest(), "HmacSHA1"));
            String sig = urlNoPad(mac.doFinal(toSign));
            return new String(payload, StandardCharsets.US_ASCII) + "." + tsSeg + "." + sig;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String urlNoPad(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static byte[] minimalBigEndian(long v) {
        int len = Math.max(1, (Long.SIZE - Long.numberOfLeadingZeros(v) + 7) / 8);
        byte[] out = new byte[len];
        for (int i = len - 1; i >= 0; i--) {
            out[i] = (byte) (v & 0xFF);
            v >>= 8;
        }
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int i = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, i, p.length);
            i += p.length;
        }
        return out;
    }

    private int seedAthleteWithMetrics(String name) {
        LocalDate today = LocalDate.now();
        return QuarkusTransaction.requiringNew().call(() -> {
            Athlete a = new Athlete();
            a.name = name;
            a.persist();
            MetricDaily m = new MetricDaily();
            m.athleteId = a.id;
            m.date = today.toString();
            m.tss = 50.0; m.ctl = 60.0; m.atl = 55.0; m.tsb = 5.0;
            m.persist();
            return a.id;
        });
    }

    @Test
    void webCookieResolvesTheAthlete() {
        int aid = seedAthleteWithMetrics("CookieUser");
        String cookie = signSession(aid, Instant.now().getEpochSecond());

        given().header("Cookie", "session=" + cookie)
                .when().get("/api/metrics/pmc")
                .then().statusCode(200)
                .body("days[0].athlete_id", equalTo(aid));
    }

    @Test
    void bearerWinsOverCookie() {
        int bearerAid = seedAthleteWithMetrics("BearerUser");
        int cookieAid = seedAthleteWithMetrics("CookieUser2");
        String token = "tok-" + java.util.UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            DeviceToken t = new DeviceToken();
            t.athleteId = bearerAid;
            t.name = "d";
            t.tokenHash = BearerAuthFilter.sha256(token);
            t.persist();
        });
        String cookie = signSession(cookieAid, Instant.now().getEpochSecond());

        // Both credentials present → Bearer wins (FastAPI parity).
        given().header("Authorization", "Bearer " + token)
                .header("Cookie", "session=" + cookie)
                .when().get("/api/metrics/pmc")
                .then().statusCode(200)
                .body("days[0].athlete_id", equalTo(bearerAid));
    }
}
