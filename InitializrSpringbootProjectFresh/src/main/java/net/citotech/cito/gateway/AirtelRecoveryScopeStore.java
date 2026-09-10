package net.citotech.cito.gateway;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Immutable provider-account attribution, recorded before dispatch. No secrets are stored here.
 * Secret rotation within the same Airtel application is permitted; changing the application,
 * credential owner, country, currency or endpoint cannot resolve an older payment.
 */
public final class AirtelRecoveryScopeStore {
    private AirtelRecoveryScopeStore() {}

    public static void record(NamedParameterJdbcTemplate jdbc, String reference, Long merchantId,
            String operation, String source, String baseUrl, String clientId, String country, String currency) {
        MapSqlParameterSource parameters = parameters(reference, merchantId, operation,
                fingerprint(source, baseUrl, clientId, country, currency));
        try {
            jdbc.update("INSERT INTO airtel_recovery_scopes (merchant_id, transaction_reference, operation, identity_hash)"
                    + " VALUES (:merchant,:reference,:operation,:identity)", parameters);
        } catch (DuplicateKeyException duplicate) {
            require(jdbc, reference, merchantId, operation, source, baseUrl, clientId, country, currency);
        }
    }

    public static void require(NamedParameterJdbcTemplate jdbc, String reference, Long merchantId,
            String operation, String source, String baseUrl, String clientId, String country, String currency) {
        String identity = fingerprint(source, baseUrl, clientId, country, currency);
        List<String> stored = jdbc.queryForList("SELECT identity_hash FROM airtel_recovery_scopes"
                + " WHERE merchant_id=:merchant AND transaction_reference=:reference AND operation=:operation",
                parameters(reference, merchantId, operation, identity), String.class);
        if (stored.size() != 1 || !MessageDigest.isEqual(identity.getBytes(StandardCharsets.UTF_8),
                stored.get(0).getBytes(StandardCharsets.UTF_8))) {
            throw new PaymentGatewayException("Airtel recovery requires the original recorded credential owner; reconcile missing or conflicting provenance");
        }
    }

    static String fingerprint(String source, String baseUrl, String clientId, String country, String currency) {
        if (blank(source) || blank(baseUrl) || blank(clientId) || blank(country) || blank(currency))
            throw new PaymentGatewayException("Airtel recovery credential scope is incomplete");
        URI uri;
        try { uri = URI.create(baseUrl.trim()); } catch (IllegalArgumentException invalid) {
            throw new PaymentGatewayException("Airtel recovery endpoint is invalid");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new PaymentGatewayException("Airtel recovery endpoint must identify one provider origin");
        String endpoint = uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                + ":" + (uri.getPort() < 0 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort())
                + (uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", ""));
        String[] fields = { source.trim().toUpperCase(Locale.ROOT), endpoint, clientId.trim(),
                country.trim().toUpperCase(Locale.ROOT), currency.trim().toUpperCase(Locale.ROOT) };
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static MapSqlParameterSource parameters(String reference, Long merchantId, String operation, String identity) {
        if (merchantId == null || merchantId <= 0 || blank(reference) || reference.length() > 255
                || !("COLLECT".equals(operation) || "PAYOUT".equals(operation)))
            throw new PaymentGatewayException("Airtel recovery transaction scope is invalid");
        return new MapSqlParameterSource("merchant", merchantId).addValue("reference", reference)
                .addValue("operation", operation).addValue("identity", identity);
    }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
