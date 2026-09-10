package net.citotech.cito.api.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.citotech.cito.api.v2.dto.PaymentResult;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.security.CanonicalRequestSigner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class IdempotencyService {
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyService(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<PaymentResult> findExisting(String merchantNumber, String key, String body) {
        Optional<String> stored = findExistingBody(merchantNumber, key, body);
        if (stored.isEmpty()) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(stored.get(), PaymentResult.class));
        } catch (Exception e) {
            throw new PaymentGatewayException("Unable to read idempotency response");
        }
    }

    public void record(String merchantNumber, String key, String body, PaymentResult result) {
        try {
            recordBody(merchantNumber, key, body, objectMapper.writeValueAsString(result));
        } catch (PaymentGatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new PaymentGatewayException("Unable to record idempotency response");
        }
    }

    public Optional<String> findExistingBody(String merchantNumber, String key, String body) {
        if (key == null || key.isBlank()) return Optional.empty();
        MapSqlParameterSource p = parameters(merchantNumber, key, body);
        try {
            jdbcTemplate.update(
                    "INSERT INTO cpay_idempotency_keys (merchant_number,idempotency_key,request_hash,response_body,status,created_at) VALUES (:merchant,:key,:hash,'null','IN_PROGRESS',CURRENT_TIMESTAMP)",
                    p);
            return Optional.empty();
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            List<Map<String, Object>> rows =
                    jdbcTemplate.queryForList(
                            "SELECT request_hash,response_body,status FROM cpay_idempotency_keys WHERE merchant_number=:merchant AND idempotency_key=:key",
                            p);
            if (rows.size() != 1)
                throw new PaymentGatewayException("Idempotency record is unavailable");
            Map<String, Object> row = rows.get(0);
            if (!p.getValue("hash").equals(row.get("request_hash")))
                throw new PaymentGatewayException(
                        "Idempotency key was reused with a different request body");
            if ("IN_PROGRESS".equals(row.get("status")))
                throw new PaymentGatewayException(
                        "Payment submission is already claimed; query its reference before retrying");
            return Optional.of(String.valueOf(row.get("response_body")));
        } catch (DataAccessException unavailable) {
            throw new PaymentGatewayException(
                    "Idempotency storage is unavailable; no submission is permitted");
        }
    }

    public void recordBody(String merchantNumber, String key, String body, String result) {
        if (key == null || key.isBlank()) return;
        MapSqlParameterSource p =
                parameters(merchantNumber, key, body).addValue("response", result);
        int changed =
                jdbcTemplate.update(
                        "UPDATE cpay_idempotency_keys SET response_body=:response,status='COMPLETED' WHERE merchant_number=:merchant AND idempotency_key=:key AND request_hash=:hash AND status='IN_PROGRESS'",
                        p);
        if (changed != 1)
            throw new PaymentGatewayException(
                    "Idempotency response could not be committed; query the payment reference");
    }

    private MapSqlParameterSource parameters(String merchant, String key, String body) {
        if (key.trim().length() > 128)
            throw new PaymentGatewayException("Idempotency key is too long");
        return new MapSqlParameterSource("merchant", merchant)
                .addValue("key", key.trim())
                .addValue("hash", CanonicalRequestSigner.sha256Hex(body == null ? "" : body));
    }
}
