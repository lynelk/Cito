package net.citotech.cito.billing.baas;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import net.citotech.cito.entitlements.CitoServiceEntitlementService;
import net.citotech.cito.gateway.PaymentGatewayException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BillingBaasApiKeyService {
    private final net.citotech.cito.developer.reference.ApiAccessBillingService apiBilling;
    private static final int DEFAULT_REQUESTS_PER_MINUTE = 300;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final CitoServiceEntitlementService entitlementService;

    public BillingBaasApiKeyService(
            NamedParameterJdbcTemplate jdbcTemplate,
            CitoServiceEntitlementService entitlementService,
            net.citotech.cito.developer.reference.ApiAccessBillingService apiBilling) {
        this.apiBilling = apiBilling;
        this.jdbcTemplate = jdbcTemplate;
        this.entitlementService = entitlementService;
    }

    @Transactional
    public BillingBaasContext authenticate(
            String apiKey,
            String environment,
            String requiredScope,
            String requestId,
            String httpMethod,
            String routeTemplate) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new PaymentGatewayException("X-Cito-Api-Key is required");
        }
        String env = normalizeEnvironment(environment);
        String scope = normalizeScope(requiredScope);
        String hash = sha256(apiKey.trim());

        MapSqlParameterSource p =
                new MapSqlParameterSource()
                        .addValue("secret_hash", hash)
                        .addValue("environment", env)
                        .addValue("scope", scope);
        List<BillingBaasContext> contexts =
                jdbcTemplate.query(
                        "SELECT bt.id AS billing_tenant_id,p.merchant_id,p.id AS project_id,"
                                + "sa.id AS service_account_id,m.environment "
                                + "FROM developer_credentials c "
                                + "JOIN developer_service_accounts sa ON sa.id=c.service_account_id "
                                + "JOIN developer_projects p ON p.id=sa.project_id "
                                + "JOIN developer_project_environments pe ON pe.project_id=p.id "
                                + "JOIN billing_tenant_developer_projects m ON m.developer_project_id=p.id "
                                + "AND m.environment=pe.environment "
                                + "JOIN billing_tenants bt ON bt.id=m.billing_tenant_id "
                                + "JOIN billing_baas_tenant_profiles bp ON bp.billing_tenant_id=bt.id "
                                + "WHERE c.secret_hash=:secret_hash AND c.status='ACTIVE' "
                                + "AND (c.expires_at IS NULL OR c.expires_at>CURRENT_TIMESTAMP) "
                                + "AND sa.status='ACTIVE' AND p.status='ACTIVE' AND pe.status='ACTIVE' "
                                + "AND m.status='ACTIVE' AND m.environment=:environment "
                                + "AND JSON_CONTAINS(sa.scopes_json,JSON_QUOTE(:scope)) "
                                + "AND (:environment<>'PRODUCTION' OR (pe.production_eligible='YES' "
                                + "AND bp.legal_model_status='APPROVED' "
                                + "AND bp.commercial_model_status='APPROVED' "
                                + "AND bp.tax_model_status='APPROVED' "
                                + "AND bp.funds_flow_status='APPROVED' "
                                + "AND bp.activation_status='ACTIVE'))",
                        p,
                        (rs, rowNum) ->
                                new BillingBaasContext(
                                        rs.getLong("billing_tenant_id"),
                                        rs.getLong("merchant_id"),
                                        rs.getLong("project_id"),
                                        rs.getLong("service_account_id"),
                                        rs.getString("environment")));
        if (contexts.isEmpty()) {
            throw new PaymentGatewayException(
                    "BaaS credential is invalid, inactive, out of scope, or not approved for "
                            + env);
        }
        if (contexts.size() != 1) {
            throw new PaymentGatewayException(
                    "BaaS credential resolves to multiple billing tenants; project mapping must be unique");
        }
        BillingBaasContext context = contexts.get(0);

        // Product access is a separate decision from credential/scope validity. Keeping both gates
        // prevents an otherwise-valid developer key from invoking a suspended Cito module.
        entitlementService.requireMerchantAccess(
                context.merchantId(),
                "BILLING",
                context.environment(),
                "SERVICE_ACCOUNT:" + context.serviceAccountId());

        enforceQuota(context);
        jdbcTemplate.update(
                "UPDATE developer_credentials SET last_used_at=CURRENT_TIMESTAMP "
                        + "WHERE secret_hash=:secret_hash",
                p);
        recordRequest(context, requestId, httpMethod, routeTemplate);
        apiBilling.admitted(context.merchantId(), context.billingTenantId(), context.environment());
        return context;
    }

    private void enforceQuota(BillingBaasContext context) {
        MapSqlParameterSource p =
                new MapSqlParameterSource()
                        .addValue("tenant", context.billingTenantId())
                        .addValue("project", context.developerProjectId())
                        .addValue("environment", context.environment());
        List<Integer> policies =
                jdbcTemplate.query(
                        "SELECT requests_per_minute FROM billing_api_quota_policies "
                                + "WHERE billing_tenant_id=:tenant AND environment=:environment "
                                + "AND status='ACTIVE' AND (developer_project_id=:project OR developer_project_id IS NULL) "
                                + "ORDER BY CASE WHEN developer_project_id=:project THEN 0 ELSE 1 END LIMIT 1",
                        p,
                        (rs, rowNum) -> rs.getInt(1));
        int limit = policies.isEmpty() ? DEFAULT_REQUESTS_PER_MINUTE : policies.get(0);
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM developer_api_request_log "
                                + "WHERE project_id=:project AND environment=:environment "
                                + "AND created_at>=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 MINUTE)",
                        p,
                        Integer.class);
        if (count != null && count >= limit) {
            throw new PaymentGatewayException("BaaS request quota exceeded");
        }
    }

    private void recordRequest(
            BillingBaasContext context, String requestId, String httpMethod, String routeTemplate) {
        String safeRequestId =
                requestId == null || requestId.isBlank()
                        ? java.util.UUID.randomUUID().toString()
                        : requestId.trim();
        jdbcTemplate.update(
                "INSERT INTO developer_api_request_log "
                        + "(merchant_id,project_id,service_account_id,request_id,http_method,route_template,environment) "
                        + "VALUES (:merchant,:project,:service_account,:request_id,:method,:route,:environment)",
                new MapSqlParameterSource()
                        .addValue("merchant", context.merchantId())
                        .addValue("project", context.developerProjectId())
                        .addValue("service_account", context.serviceAccountId())
                        .addValue("request_id", safeRequestId)
                        .addValue("method", requiredMethod(httpMethod))
                        .addValue("route", routeTemplate)
                        .addValue("environment", context.environment()));
    }

    private String normalizeEnvironment(String value) {
        if (value == null || value.isBlank()) {
            throw new PaymentGatewayException("X-Cito-Environment is required");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!"SANDBOX".equals(normalized) && !"PRODUCTION".equals(normalized)) {
            throw new PaymentGatewayException("BaaS environment must be SANDBOX or PRODUCTION");
        }
        return normalized;
    }

    private String normalizeScope(String value) {
        if (value == null || value.isBlank()) {
            throw new PaymentGatewayException("BaaS scope is required");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.startsWith("BILLING_")) {
            throw new PaymentGatewayException("Invalid BaaS billing scope");
        }
        return normalized;
    }

    private String requiredMethod(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String sha256(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
