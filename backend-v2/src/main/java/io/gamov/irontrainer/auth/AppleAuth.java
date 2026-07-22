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

    /** The native iOS app's bundle id — the identity token's `aud`. */
    @ConfigProperty(name = "apple.audience", defaultValue = "io.gamov.irontrainer.helper")
    String audience;

    private volatile ConfigurableJWTProcessor<SecurityContext> processor;

    /** (sub, email?) — email is present only on the user's first authorization. */
    public record AppleId(String sub, String email) {}

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
                    // Verifies aud == our bundle id, iss == Apple, requires sub;
                    // exp/nbf are checked by DefaultJWTClaimsVerifier automatically.
                    np.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                            audience,
                            new JWTClaimsSet.Builder().issuer(ISSUER).build(),
                            Set.of("sub")));
                    processor = p = np;
                }
                p = processor;
            }
        }
        return p;
    }

    /** Verify the token; throws 401 on any failure. */
    public AppleId verify(String identityToken) {
        try {
            JWTClaimsSet claims = processor().process(identityToken, null);
            String sub = claims.getSubject();
            if (sub == null || sub.isBlank()) {
                throw new IllegalArgumentException("token has no subject");
            }
            return new AppleId(sub, claims.getStringClaim("email"));
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            throw new WebApplicationException("Invalid Apple identity token.", 401);
        }
    }
}
