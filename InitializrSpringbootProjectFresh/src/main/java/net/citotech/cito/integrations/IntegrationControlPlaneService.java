package net.citotech.cito.integrations;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.platform.kernel.PlatformProviderRegistry;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntegrationControlPlaneService {
    private static final List<String> MATURITY_STATES =
            List.of(
                    "SUPPORTED",
                    "SANDBOX_ONLY",
                    "CERTIFICATION_PENDING",
                    "PRODUCTION_READY",
                    "SUSPENDED",
                    "UNSUPPORTED");

    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformProviderRegistry providerRegistry;

    public IntegrationControlPlaneService(
            NamedParameterJdbcTemplate jdbc, PlatformProviderRegistry providerRegistry) {
        this.jdbc = jdbc;
        this.providerRegistry = providerRegistry;
    }

    public List<Map<String, Object>> catalog() {
        return jdbc.queryForList(
                "SELECT c.connector_code connectorCode,c.connector_name connectorName,c.connector_category connectorCategory,"
                        + "c.description,c.publisher,c.auth_type authType,c.required_service_code requiredServiceCode,"
                        + "c.owning_domain owningDomain,c.capabilities_json capabilities,c.countries_json countries,"
                        + "c.environments_json environments,c.credential_schema_json credentialSchema,c.maturity_state maturityState,"
                        + "c.commercial_mode commercialMode,c.health_status healthStatus,c.health_checked_at healthCheckedAt,c.status,"
                        + "COUNT(DISTINCT i.id) installationCount,SUM(CASE WHEN i.status='ACTIVE' THEN 1 ELSE 0 END) activeInstallationCount "
                        + "FROM integration_connectors c LEFT JOIN integration_installations i ON i.connector_id=c.id "
                        + "GROUP BY c.id ORDER BY c.connector_category,c.connector_name",
                new MapSqlParameterSource());
    }

    public List<Map<String, Object>> merchantCatalog() {
        return jdbc.queryForList(
                "SELECT c.connector_code connectorCode,c.connector_name connectorName,c.connector_category connectorCategory,"
                        + "c.description,c.publisher,c.auth_type authType,c.required_service_code requiredServiceCode,"
                        + "c.owning_domain owningDomain,c.capabilities_json capabilities,c.countries_json countries,"
                        + "c.environments_json environments,c.credential_schema_json credentialSchema,c.maturity_state maturityState,"
                        + "c.commercial_mode commercialMode,c.health_status healthStatus,c.status,"
                        + "v.version_number currentVersion,v.manifest_json manifest "
                        + "FROM integration_connectors c LEFT JOIN integration_connector_versions v "
                        + "ON v.connector_id=c.id AND v.status='ACTIVE' WHERE c.status='ACTIVE' "
                        + "ORDER BY c.connector_category,c.connector_name,v.released_at DESC",
                new MapSqlParameterSource());
    }

    public List<Map<String, Object>> healthEvidence(String connectorCode, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return jdbc.queryForList(
                "SELECT h.environment,h.outcome,h.response_code responseCode,h.response_summary responseSummary,"
                        + "h.checked_by checkedBy,h.checked_at checkedAt,i.installation_reference installationReference "
                        + "FROM integration_health_checks h JOIN integration_connectors c ON c.id=h.connector_id "
                        + "LEFT JOIN integration_installations i ON i.id=h.installation_id "
                        + "WHERE c.connector_code=:connector_code ORDER BY h.id DESC LIMIT "
                        + safeLimit,
                new MapSqlParameterSource("connector_code", normalized(connectorCode)));
    }

    @Transactional
    public Map<String, Object> recordHealthEvidence(
            String connectorCode,
            String environment,
            String outcome,
            String responseCode,
            String summary,
            String actor) {
        long connectorId = connectorId(connectorCode);
        String normalizedOutcome = normalized(outcome);
        if (!List.of("HEALTHY", "DEGRADED", "UNAVAILABLE", "UNKNOWN")
                .contains(normalizedOutcome)) {
            throw new PaymentGatewayException("Unsupported connector health outcome");
        }
        String env = normalized(environment);
        if (!List.of("SANDBOX", "PRODUCTION").contains(env)) {
            throw new PaymentGatewayException("environment must be SANDBOX or PRODUCTION");
        }
        jdbc.update(
                "INSERT INTO integration_health_checks "
                        + "(connector_id,environment,outcome,response_code,response_summary,checked_by) "
                        + "VALUES (:connector_id,:environment,:outcome,:response_code,:response_summary,:checked_by)",
                new MapSqlParameterSource()
                        .addValue("connector_id", connectorId)
                        .addValue("environment", env)
                        .addValue("outcome", normalizedOutcome)
                        .addValue("response_code", blankToNull(responseCode))
                        .addValue("response_summary", safe(summary))
                        .addValue("checked_by", actor));
        jdbc.update(
                "UPDATE integration_connectors SET health_status=:health_status,health_checked_at=CURRENT_TIMESTAMP WHERE id=:id",
                new MapSqlParameterSource()
                        .addValue("id", connectorId)
                        .addValue("health_status", normalizedOutcome));
        return Map.of(
                "connectorCode", normalized(connectorCode),
                "environment", env,
                "outcome", normalizedOutcome);
    }

    @Transactional
    public Map<String, Object> updateMaturity(
            String connectorCode, String maturityState, String actor) {
        long connectorId = connectorId(connectorCode);
        String state = normalized(maturityState);
        if (!MATURITY_STATES.contains(state)) {
            throw new PaymentGatewayException("Unsupported connector maturity state");
        }
        if ("PRODUCTION_READY".equals(state)) {
            List<Map<String, Object>> rows =
                    jdbc.queryForList(
                            "SELECT health_status healthStatus FROM integration_connectors WHERE id=:id",
                            new MapSqlParameterSource("id", connectorId));
            String health = rows.isEmpty() ? "UNKNOWN" : String.valueOf(rows.get(0).get("healthStatus"));
            if (!"HEALTHY".equalsIgnoreCase(health)) {
                throw new PaymentGatewayException(
                        "Production-ready maturity requires recorded HEALTHY evidence");
            }
        }
        jdbc.update(
                "UPDATE integration_connectors SET maturity_state=:maturity_state WHERE id=:id",
                new MapSqlParameterSource()
                        .addValue("id", connectorId)
                        .addValue("maturity_state", state));
        return Map.of(
                "connectorCode", normalized(connectorCode),
                "maturityState", state,
                "updatedBy", actor);
    }

    public Map<String, Object> runtimeProvider(String providerCode) {
        return providerRegistry.find(providerCode)
                .<Map<String, Object>>map(
                        definition ->
                                Map.of(
                                        "providerCode", definition.providerCode(),
                                        "domain", definition.domain(),
                                        "capabilities", definition.capabilities(),
                                        "maturity", definition.maturity()))
                .orElseGet(
                        () ->
                                Map.of(
                                        "providerCode", normalized(providerCode),
                                        "runtimeRegistered", false));
    }

    private long connectorId(String connectorCode) {
        List<Long> rows =
                jdbc.query(
                        "SELECT id FROM integration_connectors WHERE connector_code=:connector_code",
                        new MapSqlParameterSource("connector_code", normalized(connectorCode)),
                        (rs, rowNum) -> rs.getLong("id"));
        if (rows.isEmpty()) {
            throw new PaymentGatewayException("Integration connector was not found");
        }
        return rows.get(0);
    }

    private String normalized(String value) {
        if (value == null || value.isBlank()) {
            throw new PaymentGatewayException("value is required");
        }
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }
}
