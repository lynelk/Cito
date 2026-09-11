package net.citotech.cito.merchant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.Model.MerchantUser;
import net.citotech.cito.SettingsRegistry;
import net.citotech.cito.gateway.PaymentGatewayException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MerchantEnvironmentService {
    public static final String SANDBOX = "SANDBOX";
    public static final String PRODUCTION = "PRODUCTION";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public MerchantEnvironmentService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> getPreference(MerchantUser user) {
        requireUser(user);
        String environment = currentEnvironment(user.getMerchant_id(), user.getId());
        Map<String, Object> response =
                baseEnvironmentStatus(user.getMerchant_number(), environment);
        response.put("merchantId", user.getMerchant_id());
        response.put("merchantUserId", user.getId());
        return response;
    }

    public Map<String, Object> savePreference(MerchantUser user, Map<String, Object> body) {
        requireUser(user);
        String environment =
                normalizedEnvironment(text(body == null ? null : body.get("environment")));
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("merchant_id", user.getMerchant_id());
        p.addValue("merchant_user_id", user.getId());
        p.addValue("channel_code", "*");
        p.addValue("active_environment", environment);
        p.addValue("limit_enabled", productionLimitEnabled() ? 1 : 0);
        p.addValue("limit_count", productionTransactionLimit());
        p.addValue("updated_by", user.getEmail());
        String sql =
                "INSERT INTO merchant_environment_preferences "
                        + "(merchant_id, merchant_user_id, channel_code, active_environment, production_limit_enabled, production_transaction_limit, updated_by) "
                        + "VALUES (:merchant_id, :merchant_user_id, :channel_code, :active_environment, :limit_enabled, :limit_count, :updated_by) "
                        + "ON DUPLICATE KEY UPDATE active_environment=:active_environment, production_limit_enabled=:limit_enabled, "
                        + "production_transaction_limit=:limit_count, updated_by=:updated_by, updated_at=CURRENT_TIMESTAMP";
        jdbcTemplate.update(sql, p);
        return getPreference(user);
    }

    public String resolveRequestEnvironment(String headerEnvironment, String bodyEnvironment) {
        String requested = !isBlank(headerEnvironment) ? headerEnvironment : bodyEnvironment;
        return normalizedEnvironment(requested);
    }

    public Map<String, Object> sandboxGuide(String merchantNumber) {
        Map<String, Object> guide = new LinkedHashMap<>();
        guide.put(
                "sandboxBaseUrl",
                setting("developer_sandbox_base_url", "https://sandbox.cpay.example"));
        guide.put(
                "productionBaseUrl",
                setting("developer_production_base_url", "https://api.cpay.example"));
        guide.put(
                "merchantNumber",
                isBlank(merchantNumber)
                        ? setting("developer_sandbox_merchant_number", "1000000")
                        : merchantNumber);
        guide.put("defaultCurrency", "UGX");
        guide.put("defaultCountry", "UG");
        guide.put(
                "idempotencyWindowHours",
                parseInt(setting("developer_sandbox_idempotency_hours", "24"), 24));
        guide.put("retentionDays", parseInt(setting("developer_sandbox_retention_days", "7"), 7));
        guide.put(
                "headers",
                List.of(
                        "X-CPay-Environment: SANDBOX",
                        "X-CPay-Idempotency-Key: unique-key-per-request",
                        "X-CPay-Signature: HMAC/RSA signature from your registered key"));
        guide.put(
                "environmentVariables",
                Map.of(
                        "CPAY_BASE_URL", guide.get("sandboxBaseUrl"),
                        "CPAY_MERCHANT_NUMBER", guide.get("merchantNumber"),
                        "CPAY_DEFAULT_CURRENCY", "UGX",
                        "CPAY_DEFAULT_COUNTRY", "UG",
                        "CPAY_CALLBACK_URL", "https://yourapp.example/cpay/callback"));
        guide.put(
                "testAccounts",
                List.of(
                        scenario("256770000001", "Collection succeeds", "202 then SUCCESSFUL"),
                        scenario("256770000002", "Collection declined", "202 then FAILED"),
                        scenario("256770000003", "Collection remains pending", "202 then PENDING"),
                        scenario("256770000004", "Provider timeout", "202 then UNKNOWN"),
                        scenario("256770000005", "Unsupported account", "Rejected"),
                        scenario("256750000001", "Payout succeeds", "202 then SUCCESSFUL"),
                        scenario("256750000002", "Payout fails", "202 then FAILED"),
                        scenario("256780000001", "SMS accepted", "ACCEPTED"),
                        scenario("256780000002", "SMS rejected", "REJECTED")));
        return guide;
    }

    public Map<String, Object> baseEnvironmentStatus(String merchantNumber, String environment) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("environment", normalizedEnvironment(environment));
        response.put("sandbox", sandboxGuide(merchantNumber));
        response.put("productionLimit", productionLimitStatus(merchantNumber));
        return response;
    }

    public void enforceProductionLimit(Merchant merchant, String environment) {
        if (!PRODUCTION.equals(normalizedEnvironment(environment))) {
            return;
        }
        if (!productionLimitEnabled()) {
            return;
        }
        int limit = productionTransactionLimit();
        if (limit < 1) {
            return;
        }
        int used =
                productionTransactionsToday(merchant == null ? null : merchant.getAccount_number());
        if (used >= limit) {
            throw new PaymentGatewayException(
                    "Production daily transaction limit reached ("
                            + used
                            + "/"
                            + limit
                            + "). Ask an administrator to raise or disable the limit.");
        }
    }

    public String normalizedEnvironment(String environment) {
        if (environment == null || environment.trim().isEmpty()) {
            return SANDBOX;
        }
        return PRODUCTION.equalsIgnoreCase(environment.trim()) ? PRODUCTION : SANDBOX;
    }

    private String currentEnvironment(Long merchantId, Long merchantUserId) {
        try {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("merchant_id", merchantId);
            p.addValue("merchant_user_id", merchantUserId);
            String sql =
                    "SELECT active_environment FROM merchant_environment_preferences "
                            + "WHERE merchant_id=:merchant_id AND merchant_user_id=:merchant_user_id AND channel_code='*' "
                            + "ORDER BY id DESC LIMIT 1";
            String value = jdbcTemplate.queryForObject(sql, p, String.class);
            return normalizedEnvironment(value);
        } catch (DataAccessException e) {
            return SANDBOX;
        }
    }

    public Map<String, Object> productionLimitStatus(String merchantNumber) {
        boolean enabled = productionLimitEnabled();
        int limit = productionTransactionLimit();
        int used = productionTransactionsToday(merchantNumber);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("limit", limit);
        status.put("usedToday", used);
        status.put("remainingToday", enabled && limit > 0 ? Math.max(0, limit - used) : null);
        return status;
    }

    private int productionTransactionsToday(String merchantNumber) {
        if (isBlank(merchantNumber)) {
            return 0;
        }
        try {
            String sql =
                    "SELECT (SELECT COUNT(*) FROM provider_endpoint_runs "
                            + "WHERE merchant_number=:merchant_number AND environment='PRODUCTION' AND created_at >= CURRENT_DATE()) "
                            + "+ (SELECT COUNT(*) FROM mobile_money_executions e JOIN merchants m ON m.id=e.merchant_id "
                            + "WHERE m.account_number=:merchant_number AND e.environment='PRODUCTION' AND e.created_at >= CURRENT_DATE())";
            Integer count =
                    jdbcTemplate.queryForObject(
                            sql,
                            new MapSqlParameterSource("merchant_number", merchantNumber),
                            Integer.class);
            return count == null ? 0 : count;
        } catch (DataAccessException e) {
            throw new PaymentGatewayException(
                    "Production usage is unavailable; submission is not permitted");
        }
    }

    private boolean productionLimitEnabled() {
        return SettingsRegistry.getBoolean("production_transaction_limit_enabled", jdbcTemplate);
    }

    private int productionTransactionLimit() {
        return SettingsRegistry.getInt("production_transaction_limit_count", jdbcTemplate);
    }

    private String setting(String name, String defaultValue) {
        try {
            String sql = "SELECT setting_value FROM settings WHERE name=:name LIMIT 1";
            String value =
                    jdbcTemplate.queryForObject(
                            sql, new MapSqlParameterSource("name", name), String.class);
            return isBlank(value) ? defaultValue : value.trim();
        } catch (DataAccessException e) {
            return defaultValue;
        }
    }

    private Map<String, String> scenario(String account, String description, String expected) {
        Map<String, String> scenario = new LinkedHashMap<>();
        scenario.put("account", account);
        scenario.put("description", description);
        scenario.put("expected", expected);
        return scenario;
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private void requireUser(MerchantUser user) {
        if (user == null || user.getMerchant_id() == null) {
            throw new PaymentGatewayException("Merchant login is required");
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
