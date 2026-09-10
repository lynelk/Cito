package net.citotech.cito.portal;

import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.citotech.cito.Model.MerchantUser;
import net.citotech.cito.Model.User;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v2/portal", produces = "application/json")
public class PortalV2Controller {
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PortalV2Controller(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/session")
    public Map<String, Object> session(HttpSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        Object admin = session.getAttribute("user");
        Object merchant = session.getAttribute("merchantUser");
        response.put("authenticated", admin != null || merchant != null);
        if (admin instanceof User user) {
            response.put("type", "ADMIN");
            response.put("user", userInfo(user));
        } else if (merchant instanceof MerchantUser user) {
            response.put("type", "MERCHANT");
            Map<String, Object> info = userInfo(user);
            info.put("merchantId", user.getMerchant_id());
            response.put("user", info);
        }
        return response;
    }

    @GetMapping("/dashboard/summary")
    public Map<String, Object> dashboardSummary(HttpSession session) {
        Object merchantSession = session == null ? null : session.getAttribute("merchantUser");
        MerchantUser merchantUser =
                merchantSession instanceof MerchantUser ? (MerchantUser) merchantSession : null;
        boolean merchantScoped = merchantUser != null && merchantUser.getMerchant_id() != null;
        if (!merchantScoped) requireAdministrator();
        MapSqlParameterSource scope = new MapSqlParameterSource();
        if (merchantScoped) {
            scope.addValue("merchant_id", merchantUser.getMerchant_id());
            scope.addValue("merchant_number", merchantUser.getMerchant_number());
        }
        String environment =
                currentEnvironment(
                        merchantScoped ? merchantUser.getMerchant_id() : null,
                        merchantScoped ? merchantUser.getId() : null);
        boolean sandbox = "SANDBOX".equals(environment);
        String transactionView =
                sandbox ? "merchant_sandbox_transactions" : "merchant_production_transactions";
        scope.addValue("environment", environment);
        String txScope = merchantScoped ? " WHERE merchant_id=:merchant_id" : "";
        String txAndScope = merchantScoped ? " AND merchant_id=:merchant_id" : "";
        String runScope =
                " WHERE environment=:environment"
                        + (merchantScoped ? " AND merchant_number=:merchant_number" : "");
        String credentialScope =
                " WHERE environment=:environment"
                        + (merchantScoped ? " AND merchant_id=:merchant_id" : "");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scope", merchantScoped ? "MERCHANT" : "ADMIN");
        response.put("environment", environment);
        response.put(
                "productionLimit",
                productionLimitStatus(merchantScoped ? merchantUser.getMerchant_number() : null));
        response.put("merchants", merchantScoped ? 1 : scalarInt("SELECT COUNT(*) FROM merchants"));
        response.put(
                "transactions",
                scalarInt("SELECT COUNT(*) FROM " + transactionView + txScope, scope));
        response.put(
                "payIns",
                scalarDecimal(
                        "SELECT COALESCE(SUM(original_amount),0) FROM "
                                + transactionView
                                + " WHERE tx_type='PAYIN'"
                                + txAndScope,
                        scope));
        response.put(
                "payOuts",
                scalarDecimal(
                        "SELECT COALESCE(SUM(original_amount),0) FROM "
                                + transactionView
                                + " WHERE tx_type='PAYOUT'"
                                + txAndScope,
                        scope));
        response.put(
                "pendingCallbacks",
                sandbox
                        ? 0
                        : scalarInt(
                                "SELECT COUNT(*) FROM callback_tasks WHERE task_status IN ('PENDING','RETRY','PARKED')"
                                        + txAndScope,
                                scope));
        response.put("smsBatches", scalarInt("SELECT COUNT(*) FROM merchant_sms" + txScope, scope));
        response.put(
                "channelBalances",
                sandbox
                        ? List.of()
                        : rows(
                                "SELECT merchant_id, channel_code, gateway_id, currency, available_balance, ledger_balance, pending_balance "
                                        + "FROM merchant_channel_balances"
                                        + txScope
                                        + " ORDER BY updated_at DESC LIMIT 50",
                                scope));
        response.put(
                "channelMetrics",
                rows(
                        "SELECT channel_code, environment, COUNT(*) AS total, "
                                + "SUM(CASE WHEN run_status IN ('SUCCESS','SUCCESSFUL','SUBMITTED','SANDBOX_ACCEPTED') THEN 1 ELSE 0 END) AS successful, "
                                + "SUM(CASE WHEN run_status IN ('FAILED','SANDBOX_FAILED','SANDBOX_REJECTED') THEN 1 ELSE 0 END) AS failed, "
                                + "MAX(created_at) AS last_activity "
                                + "FROM provider_endpoint_runs"
                                + runScope
                                + " GROUP BY channel_code, environment ORDER BY total DESC LIMIT 25",
                        scope));
        response.put(
                "activeChannels",
                rows(
                        "SELECT channel_code, display_name, environment, status, COUNT(*) AS merchants "
                                + "FROM merchant_channel_credentials"
                                + credentialScope
                                + " GROUP BY channel_code, display_name, environment, status ORDER BY channel_code, environment",
                        scope));
        response.put(
                "recentNotifications",
                merchantScoped
                        ? List.of()
                        : rows(
                                "SELECT id, alert_type, alert_status, severity, reference_value, message, created_at "
                                        + "FROM operations_alerts ORDER BY id DESC LIMIT 20",
                                new MapSqlParameterSource()));
        return response;
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/merchants")
    public Map<String, Object> merchants(
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        int safeLimit = limit(limit);
        MapSqlParameterSource p = new MapSqlParameterSource("limit", safeLimit);
        List<Map<String, Object>> rows =
                rows(
                        "SELECT id, name, short_name, account_number, account_type, status, created_by, created_on "
                                + "FROM merchants ORDER BY id DESC LIMIT :limit",
                        p);
        return listResponse(rows, safeLimit);
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/settings")
    public Map<String, Object> settings() {
        List<Map<String, Object>> rows =
                rows(
                        "SELECT id, label, name, setting_value, description, setting_group FROM settings ORDER BY setting_group, label",
                        new MapSqlParameterSource());
        return listResponse(rows, rows.size());
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/transactions")
    public Map<String, Object> transactions(
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        int safeLimit = limit(limit);
        MapSqlParameterSource p = new MapSqlParameterSource("limit", safeLimit);
        List<Map<String, Object>> rows =
                rows(
                        "SELECT id, merchant_id, gateway_id, original_amount, charges, status, tx_unique_id, tx_gateway_ref, "
                                + "tx_merchant_ref, created_on, tx_type, payer_number, currency, callback_status "
                                + "FROM merchant_production_transactions ORDER BY id DESC LIMIT :limit",
                        p);
        return listResponse(rows, safeLimit);
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/sms")
    public Map<String, Object> sms(@RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        int safeLimit = limit(limit);
        MapSqlParameterSource p = new MapSqlParameterSource("limit", safeLimit);
        List<Map<String, Object>> rows =
                rows(
                        "SELECT id, merchant_id, total_recipients, status, smsgw, created_by, send_time, total_amount "
                                + "FROM merchant_sms ORDER BY id DESC LIMIT :limit",
                        p);
        return listResponse(rows, safeLimit);
    }

    private void requireAdministrator() {
        var authentication =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream()
                        .noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())))
            throw new org.springframework.security.access.AccessDeniedException(
                    "Administrator authorization or a scoped merchant session is required");
    }

    private Map<String, Object> userInfo(User user) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", user.getId());
        info.put("name", user.getName());
        info.put("email", user.getEmail());
        info.put("phone", user.getPhone());
        return info;
    }

    private Map<String, Object> listResponse(List<Map<String, Object>> rows, int limit) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", rows);
        response.put("count", rows.size());
        response.put("limit", limit);
        return response;
    }

    private List<Map<String, Object>> rows(String sql, MapSqlParameterSource parameters) {
        try {
            return jdbcTemplate.queryForList(sql, parameters);
        } catch (DataAccessException e) {
            return List.of();
        }
    }

    private Integer scalarInt(String sql) {
        return scalarInt(sql, new MapSqlParameterSource());
    }

    private Integer scalarInt(String sql, MapSqlParameterSource parameters) {
        try {
            Integer value = jdbcTemplate.queryForObject(sql, parameters, Integer.class);
            return value == null ? 0 : value;
        } catch (DataAccessException e) {
            return 0;
        }
    }

    private BigDecimal scalarDecimal(String sql) {
        return scalarDecimal(sql, new MapSqlParameterSource());
    }

    private BigDecimal scalarDecimal(String sql, MapSqlParameterSource parameters) {
        try {
            BigDecimal value = jdbcTemplate.queryForObject(sql, parameters, BigDecimal.class);
            return value == null ? BigDecimal.ZERO : value;
        } catch (DataAccessException e) {
            return BigDecimal.ZERO;
        }
    }

    private String currentEnvironment(Long merchantId, Long merchantUserId) {
        if (merchantId == null || merchantUserId == null) {
            return "PRODUCTION";
        }
        try {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("merchant_id", merchantId);
            p.addValue("merchant_user_id", merchantUserId);
            String sql =
                    "SELECT active_environment FROM merchant_environment_preferences "
                            + "WHERE merchant_id=:merchant_id AND merchant_user_id=:merchant_user_id AND channel_code='*' "
                            + "ORDER BY id DESC LIMIT 1";
            String value = jdbcTemplate.queryForObject(sql, p, String.class);
            return value == null || value.trim().isEmpty() ? "SANDBOX" : value.trim().toUpperCase();
        } catch (DataAccessException e) {
            return "SANDBOX";
        }
    }

    private Map<String, Object> productionLimitStatus(String merchantNumber) {
        return new net.citotech.cito.merchant.MerchantEnvironmentService(jdbcTemplate)
                .productionLimitStatus(merchantNumber);
    }

    private int limit(int requested) {
        if (requested < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
