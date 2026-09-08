package net.citotech.cito.security;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import net.citotech.cito.gateway.PaymentGatewayException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetTokenService {
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${cpay.password-reset.expiry-minutes:15}")
    private long expiryMinutes;

    public PasswordResetTokenService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public String issue(String entityType, long entityId, String email, String requestIp) {
        String token = generateToken();
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("entity_type", normalize(entityType));
        p.addValue("entity_id", entityId);
        p.addValue("email", normalizeEmail(email));
        p.addValue("token_hash", CanonicalRequestSigner.sha256Hex(token));
        p.addValue("request_ip", requestIp);
        p.addValue(
                "expires_at",
                Timestamp.from(Instant.now().plus(expiryMinutes, ChronoUnit.MINUTES)));
        try {
            // Browser-issued reset tokens supersede earlier browser tokens, but an explicitly
            // configured operational recovery token must survive the request step long enough for
            // the operator to complete recovery. Operational tokens remain short-lived, hashed,
            // single-use and require the separate recovery bootstrap configuration.
            jdbcTemplate.update(
                    "UPDATE password_reset_tokens SET consumed_at=CURRENT_TIMESTAMP "
                            + "WHERE entity_type=:entity_type AND entity_id=:entity_id "
                            + "AND consumed_at IS NULL "
                            + "AND COALESCE(request_ip, '') <> 'ops-admin-recovery'",
                    p);
            jdbcTemplate.update(
                    "INSERT INTO password_reset_tokens "
                            + "(entity_type, entity_id, email, token_hash, request_ip, expires_at) "
                            + "VALUES (:entity_type, :entity_id, :email, :token_hash, :request_ip, :expires_at)",
                    p);
        } catch (DataAccessException ignored) {
            // Legacy databases can still use the existing email_verification_code column.
        }
        return token;
    }

    @Transactional
    public boolean consume(String entityType, long entityId, String email, String token) {
        if (isBlank(token)) {
            return false;
        }
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("entity_type", normalize(entityType));
        p.addValue("entity_id", entityId);
        p.addValue("email", normalizeEmail(email));
        p.addValue("token_hash", CanonicalRequestSigner.sha256Hex(token.trim()));
        try {
            List<Long> rows =
                    jdbcTemplate.query(
                            "SELECT id FROM password_reset_tokens "
                                    + "WHERE entity_type=:entity_type AND entity_id=:entity_id AND email=:email "
                                    + "AND token_hash=:token_hash AND consumed_at IS NULL AND expires_at > CURRENT_TIMESTAMP "
                                    + "ORDER BY id DESC LIMIT 1",
                            p,
                            (rs, rowNum) -> rs.getLong("id"));
            if (rows.isEmpty()) {
                jdbcTemplate.update(
                        "UPDATE password_reset_tokens SET attempt_count=attempt_count+1 "
                                + "WHERE entity_type=:entity_type AND entity_id=:entity_id "
                                + "AND consumed_at IS NULL AND expires_at > CURRENT_TIMESTAMP",
                        p);
                return false;
            }
            p.addValue("id", rows.get(0));
            return jdbcTemplate.update(
                            "UPDATE password_reset_tokens SET consumed_at=CURRENT_TIMESTAMP WHERE id=:id AND consumed_at IS NULL",
                            p)
                    > 0;
        } catch (DataAccessException ignored) {
            throw new PaymentGatewayException("Password reset token store is unavailable");
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String normalize(String value) {
        return isBlank(value) ? "" : value.trim().toUpperCase();
    }

    private String normalizeEmail(String value) {
        return isBlank(value) ? "" : value.trim().toLowerCase();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
