package net.citotech.cito;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lightweight health/status endpoint for monitoring and load-balancer probes. GET /status/health —
 * returns HTTP 200 only when its database-backed checks succeed.
 */
@RestController
@RequestMapping(path = "/status")
public class HealthController {

    private static final Logger logger = Logger.getLogger(HealthController.class.getName());

    @Autowired NamedParameterJdbcTemplate jdbcTemplate;

    @Value("${custom.gatewaystate}")
    private String gatewaystate;

    @Value("${RAILWAY_GIT_COMMIT_SHA:${CITO_RELEASE_SHA:unknown}}")
    private String releaseSha;

    @GetMapping(path = "/health", produces = "application/json")
    public ResponseEntity<String> health() {
        JSONObject result = new JSONObject();
        try {
            // DB connectivity check
            boolean dbOk = checkDb();
            result.put("db", dbOk ? "UP" : "DOWN");

            // Pending (stuck) transactions older than 30 minutes
            long pendingCount = countPendingTransactions();
            result.put("pending_transactions", pendingCount);

            // Failed callbacks waiting for retry
            long failedCallbacks = countFailedCallbacks();
            result.put("failed_callbacks", failedCallbacks);

            // Gateway operating mode (live / sandbox)
            result.put("gateway_mode", gatewaystate != null ? gatewaystate : "unknown");
            result.put(
                    "release_sha",
                    releaseSha == null || releaseSha.isBlank() ? "unknown" : releaseSha);

            boolean healthy = dbOk && pendingCount >= 0 && failedCallbacks >= 0;
            String overallStatus = healthy ? "UP" : "DOWN";
            result.put("status", overallStatus);
            result.put("code", healthy ? "000" : "503");
            return ResponseEntity.status(healthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                    .body(result.toString());
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Health check error: " + e.getMessage(), e);
            try {
                result.put("status", "ERROR");
                result.put("code", "503");
                result.put("db", "DOWN");
                result.put(
                        "release_sha",
                        releaseSha == null || releaseSha.isBlank() ? "unknown" : releaseSha);
            } catch (JSONException ignored) {
            }
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result.toString());
        }
    }

    private boolean checkDb() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", new MapSqlParameterSource(), Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private long countPendingTransactions() {
        try {
            String sql =
                    "SELECT COUNT(*) FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " WHERE status='PENDING' AND created_on < DATE_SUB(NOW(), INTERVAL 30"
                            + " MINUTE)";
            Long count = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource(), Long.class);
            return count != null ? count : 0L;
        } catch (Exception e) {
            return -1L;
        }
    }

    private long countFailedCallbacks() {
        try {
            String sql =
                    "SELECT COUNT(*) FROM "
                            + Common.DB_TABLE_MERCHANT_TRANSACTION_LOG
                            + " WHERE callback_status='FAILED'";
            Long count = jdbcTemplate.queryForObject(sql, new MapSqlParameterSource(), Long.class);
            return count != null ? count : 0L;
        } catch (Exception e) {
            return -1L;
        }
    }
}
