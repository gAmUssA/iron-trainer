package io.gamov.irontrainer.util;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Random-token helpers (Python `secrets`). A fresh SecureRandom per call —
 * minting is infrequent, and a static instance would be baked into the native
 * image heap with a cached seed, which GraalVM rejects (and would be insecure). */
public final class SecureTokens {

    private SecureTokens() {
    }

    /** secrets.token_urlsafe(nbytes): urlsafe base64, no padding. */
    public static String urlsafe(int nbytes) {
        byte[] b = new byte[nbytes];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** secrets.token_hex(nbytes): 2 lowercase hex chars per byte. */
    public static String hex(int nbytes) {
        byte[] b = new byte[nbytes];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
