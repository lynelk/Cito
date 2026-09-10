package net.citotech.cito.gateway;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/** Credential-scoped encrypted-cache keys and conservative provider-token expiry. */
public final class ProviderTokenScope {
    private ProviderTokenScope() {}

    public static String segment(String product, String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return product.toUpperCase(Locale.ROOT)
                    + ":"
                    + HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new PaymentGatewayException("Unable to derive provider token scope");
        }
    }

    public static Instant expiresAt(long providerSeconds) {
        if (providerSeconds <= 0) {
            throw new PaymentGatewayException("Provider token lifetime must be positive");
        }
        long boundedSeconds = Math.min(providerSeconds, 86400L);
        long skew = Math.min(60L, boundedSeconds / 10L);
        return Instant.now().plusSeconds(boundedSeconds - skew);
    }
}
