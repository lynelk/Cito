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
    private final ThreadLocal<RequestScope> requestScope = new ThreadLocal<>();

    public IdempotencyService(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<PaymentResult> findExisting(String merchantNumber, String key, String body) {
        Optional<String> stored = claim(merchantNumber, key, body, false);
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
        return claim(merchantNumber, key, body, true);
    }

    private Optional<String> claim(String merchantNumber, String key, String body, boolean legacy) {
        if (key == null || key.isBlank()) return Optional.empty();
        MapSqlParameterSource p =
                parameters(merchantNumber, key, body).addValue("failure", failureResponse(legacy));
        try {
            jdbcTemplate.update(
                    "INSERT INTO cpay_idempotency_keys (merchant_number,idempotency_key,request_hash,response_body,status,created_at) VALUES (:merchant,:key,:hash,:failure,'IN_PROGRESS',CURRENT_TIMESTAMP)",
                    p);
            RequestScope scope = requestScope.get();
            if (scope != null) scope.claims.add(new Claim(p, legacy));
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

    private String failureResponse(boolean legacy) {
        String message =
                "Request did not complete. Check the payment reference before retrying with a new idempotency key.";
        return legacy
                ? new org.json.JSONObject()
                        .put("state", "ERROR")
                        .put("code", "102")
                        .put("message", message)
                        .toString()
                : new org.json.JSONObject()
                        .put("status", "REQUEST_FAILED")
                        .put("message", message)
                        .toString();
    }

    /**
     * Crash recovery keeps the key bound and returns its persisted failure template; it never makes
     * an ambiguous provider submission eligible for automatic replay.
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${cpay.idempotency.recovery.delay-ms:60000}")
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(
            name = "idempotencyFailureRecovery",
            lockAtMostFor = "PT1M")
    public void recoverAbandonedClaims() {
        jdbcTemplate.update(
                "UPDATE cpay_idempotency_keys SET status='COMPLETED' WHERE status='IN_PROGRESS' AND response_body<>'null' AND created_at<DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 15 MINUTE)",
                new MapSqlParameterSource());
    }

    /** Only claims acquired by this request may be finalized by its cleanup. */
    public RequestScope openRequestScope() {
        RequestScope scope = new RequestScope(requestScope.get());
        requestScope.set(scope);
        return scope;
    }

    public final class RequestScope implements AutoCloseable {
        private final RequestScope previous;
        private final List<Claim> claims = new java.util.ArrayList<>();

        private RequestScope(RequestScope previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            try {
                for (Claim claim : claims) {
                    // A request error is not a provider decline. Keep the commercial identity
                    // bound and replay an explicit request failure, rather than an eternal busy
                    // claim or an unsafe automatic resubmission after an ambiguous outcome.
                    String failure = failureResponse(claim.legacy());
                    try {
                        jdbcTemplate.update(
                                "UPDATE cpay_idempotency_keys SET response_body=:response,status='COMPLETED' WHERE merchant_number=:merchant AND idempotency_key=:key AND request_hash=:hash AND status='IN_PROGRESS'",
                                new MapSqlParameterSource(claim.parameters().getValues())
                                        .addValue("response", failure));
                    } catch (DataAccessException unavailable) {
                        org.slf4j.LoggerFactory.getLogger(IdempotencyService.class)
                                .warn(
                                        "Idempotency failure response could not be stored; the claim remains closed to resubmission");
                    }
                }
            } finally {
                if (previous == null) requestScope.remove();
                else requestScope.set(previous);
            }
        }
    }

    private record Claim(MapSqlParameterSource parameters, boolean legacy) {}
}
