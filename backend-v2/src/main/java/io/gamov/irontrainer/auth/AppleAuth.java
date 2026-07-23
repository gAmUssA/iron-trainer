package io.gamov.irontrainer.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;

/**
 * Verifies a Sign-in-with-Apple identity token (a JWT signed by Apple) against
 * Apple's public JWKS and returns the stable user id. The keys are fetched from
 * appleid.apple.com and cached/refreshed by nimbus. This is used ONCE at sign-in
 * (AppleResource), not for per-request endpoint auth — we mint our own bearer
 * from the result. (bean 3e6w)
 */
@ApplicationScoped
public class AppleAuth {
    private static final String ISSUER = "https://appleid.apple.com";
    private static final String JWKS_URL = "https://appleid.apple.com/auth/keys";

    /** Accepted `aud` values for the identity token: the native app's bundle id
     * AND the web Service ID (Sign in with Apple JS uses a different audience than
     * the native app). Comma-separated. */
    @ConfigProperty(name = "apple.audiences",
            defaultValue = "io.gamov.irontrainer.helper,io.gamov.irontrainer.web")
    String audiences;

    private volatile ConfigurableJWTProcessor<SecurityContext> processor;

    /** The stable Apple user id (`sub`). */
    public record AppleId(String sub) {}

    private ConfigurableJWTProcessor<SecurityContext> processor() throws Exception {
        ConfigurableJWTProcessor<SecurityContext> p = processor;
        if (p == null) {
            synchronized (this) {
                if (processor == null) {
                    JWKSource<SecurityContext> keys =
                            JWKSourceBuilder.create(new URI(JWKS_URL).toURL()).build();
                    JWSKeySelector<SecurityContext> selector =
                            new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys);
                    ConfigurableJWTProcessor<SecurityContext> np = new DefaultJWTProcessor<>();
                    np.setJWSKeySelector(selector);
                    Set<String> accepted = new HashSet<>();
                    for (String a : audiences.split(",")) {
                        if (!a.isBlank()) accepted.add(a.trim());
                    }
                    // Verifies aud ∈ accepted, iss == Apple, requires sub;
                    // exp/nbf are checked by DefaultJWTClaimsVerifier automatically.
                    np.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                            accepted,
                            new JWTClaimsSet.Builder().issuer(ISSUER).build(),
                            Set.of("sub"),
                            null));
                    processor = p = np;
                }
                p = processor;
            }
        }
        return p;
    }

    /** Verify the token: 401 on an invalid/expired/wrong-audience token, but 503
     * when Apple's key service can't be reached (an outage is not a bad token —
     * a valid user must not be told their token is invalid, and it's retriable). */
    public AppleId verify(String identityToken) {
        try {
            JWTClaimsSet claims = processor().process(identityToken, null);
            String sub = claims.getSubject();
            if (sub == null || sub.isBlank()) {
                throw new WebApplicationException("Apple token has no subject.", 401);
            }
            return new AppleId(sub);
        } catch (WebApplicationException e) {
            throw e;
        } catch (com.nimbusds.jose.KeySourceException e) {
            throw new WebApplicationException("Apple key service unavailable — try again.", 503);
        } catch (Exception e) {
            throw new WebApplicationException("Invalid Apple identity token.", 401);
        }
    }
}
