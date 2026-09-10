package net.citotech.cito.commercial;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.citotech.cito.gateway.PaymentGatewayException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commercial control plane for Cito packaging, the Founding 20 merchant cohort and the embedded
 * partner programme. This service deliberately references the existing lifecycle, entitlement and
 * Embedded Cito records instead of duplicating activation or product-access state.
 */
@Service
public class CitoCommercialProgrammeService {
    private static final Set<String> PACKAGE_STATUSES =
            Set.of("DRAFT", "SUBMITTED", "ACTIVE", "SUSPENDED", "RETIRED");
    private static final Set<String> ASSIGNMENT_STATUSES =
            Set.of("PROPOSED", "APPROVED", "ACTIVE", "SUSPENDED", "CANCELLED");
    private static final Set<String> FOUNDING_STATUSES =
            Set.of("CANDIDATE", "INVITED", "ONBOARDING", "LIVE", "RETAINED", "AT_RISK", "EXITED");
    private static final Set<String> PARTNER_STATUSES =
            Set.of("CANDIDATE", "QUALIFIED", "ONBOARDING", "LIVE", "SCALING", "AT_RISK", "EXITED");

    private final NamedParameterJdbcTemplate jdbc;

    public CitoCommercialProgrammeService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> savePackage(
            String packageCode,
            String packageName,
            String targetSegment,
            String description,
            String serviceCodesJson,
            String onboardingSupportLevel,
            String commercialTermsJson,
            String actor) {
        String code = required(packageCode, "packageCode").toUpperCase(Locale.ROOT);
        MapSqlParameterSource p =
                new MapSqlParameterSource()
                        .addValue("code", code)
                        .addValue("name", required(packageName, "packageName"))
                        .addValue("segment", blankToNull(targetSegment))
                        .addValue("description", blankToNull(description))
                        .addValue("services", requiredJsonArray(serviceCodesJson, "serviceCodesJson"))
                        .addValue("support", normalized(onboardingSupportLevel, "STANDARD"))
                        .addValue("terms", jsonOrNull(commercialTermsJson))
                        .addValue("actor", blankToNull(actor));
        jdbc.update(
                "INSERT INTO cito_commercial_packages "
                        + "(package_code,package_name,target_segment,description,service_codes_json,onboarding_support_level,commercial_terms_json,status,created_by) "
                        + "VALUES (:code,:name,:segment,:description,:services,:support,:terms,'DRAFT',:actor) "
                        + "ON DUPLICATE KEY UPDATE package_name=VALUES(package_name),target_segment=VALUES(target_segment),"
                        + "description=VALUES(description),service_codes_json=VALUES(service_codes_json),"
                        + "onboarding_support_level=VALUES(onboarding_support_level),commercial_terms_json=VALUES(commercial_terms_json),"
                        + "version_number=version_number+1,status=CASE WHEN status='ACTIVE' THEN 'DRAFT' ELSE status END,updated_at=CURRENT_TIMESTAMP",
                p);
        return packageByCode(code);
    }

    @Transactional
    public Map<String, Object> submitPackage(String packageCode, String actor) {
        return transitionPackage(packageCode, "DRAFT", "SUBMITTED", actor, false);
    }

    @Transactional
    public Map<String, Object> approvePackage(String packageCode, String actor) {
        String code = required(packageCode, "packageCode").toUpperCase(Locale.ROOT);
        String approver = required(actor, "actor");
        int updated =
                jdbc.update(
                        "UPDATE cito_commercial_packages SET status='ACTIVE',approved_by=:actor,approved_at=CURRENT_TIMESTAMP "
                                + "WHERE package_code=:code AND status='SUBMITTED' "
                                + "AND (created_by IS NULL OR created_by<>:actor)",
                        new MapSqlParameterSource("code", code).addValue("actor", approver));
        if (updated == 0) {
            throw new PaymentGatewayException(
                    "Package approval requires SUBMITTED status and, when known, a different approver");
        }
        return packageByCode(code);
    }

    public List<Map<String, Object>> packages() {
        return jdbc.queryForList(
                "SELECT package_code AS packageCode,package_name AS packageName,target_segment AS targetSegment,"
                        + "description,service_codes_json AS serviceCodes,onboarding_support_level AS onboardingSupportLevel,"
                        + "commercial_terms_json AS commercialTerms,status,version_number AS versionNumber,"
                        + "created_by AS createdBy,approved_by AS approvedBy,approved_at AS approvedAt,updated_at AS updatedAt "
                        + "FROM cito_commercial_packages ORDER BY CASE status WHEN 'ACTIVE' THEN 1 WHEN 'SUBMITTED' THEN 2 ELSE 3 END,package_name",
                new MapSqlParameterSource());
    }

    @Transactional
    public Map<String, Object> assignPackage(
            long merchantId,
            String packageCode,
            String environment,
            Instant effectiveFrom,
            Instant effectiveTo,
            String notes,
            String actor) {
        requireMerchant(merchantId);
        String env = normalizeEnvironment(environment);
        if (effectiveFrom != null && effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new PaymentGatewayException("effectiveTo must be after effectiveFrom");
        }
        long packageId = activePackageId(packageCode);
        MapSqlParameterSource p =
                new MapSqlParameterSource()
                        .addValue("merchant", merchantId)
                        .addValue("package", packageId)
                        .addValue("environment", env)
                        .addValue("from", effectiveFrom == null ? null : Timestamp.from(effectiveFrom))
                        .addValue("to", effectiveTo == null ? null : Timestamp.from(effectiveTo))
                        .addValue("notes", blankToNull(notes))
                        .addValue("actor", blankToNull(actor));
        jdbc.update(
                "INSERT INTO merchant_commercial_package_assignments "
                        + "(merchant_id,package_id,environment,status,effective_from,effective_to,assigned_by,notes) "
                        + "VALUES (:merchant,:package,:environment,'PROPOSED',:from,:to,:actor,:notes) "
                        + "ON DUPLICATE KEY UPDATE status='PROPOSED',effective_from=VALUES(effective_from),"
                        + "effective_to=VALUES(effective_to),assigned_by=VALUES(assigned_by),notes=VALUES(notes),updated_at=CURRENT_TIMESTAMP",
                p);
        return merchantCommercialSummary(merchantId);
    }

    @Transactional
    public Map<String, Object> activateAssignment(
            long merchantId, String packageCode, String environment, String actor) {
        String env = normalizeEnvironment(environment);
        String code = required(packageCode, "packageCode").toUpperCase(Locale.ROOT);
        int updated =
                jdbc.update(
                        "UPDATE merchant_commercial_package_assignments a "
                                + "JOIN cito_commercial_packages p ON p.id=a.package_id "
                                + "SET a.status='ACTIVE',a.approved_by=:actor "
                                + "WHERE a.merchant_id=:merchant AND p.package_code=:code AND a.environment=:environment "
                                + "AND a.status IN ('PROPOSED','APPROVED') AND p.status='ACTIVE' "
                                + "AND (a.effective_from IS NULL OR a.effective_from<=CURRENT_TIMESTAMP) "
                                + "AND (a.effective_to IS NULL OR a.effective_to>CURRENT_TIMESTAMP)",
                        new MapSqlParameterSource("merchant", merchantId)
                                .addValue("code", code)
                                .addValue("environment", env)
                                .addValue("actor", required(actor, "actor")));
        if (updated == 0) {
            throw new PaymentGatewayException(
                    "Commercial package assignment is not eligible for activation");
        }
        return merchantCommercialSummary(merchantId);
    }

    public Map<String, Object> merchantCommercialSummary(long merchantId) {
        requireMerchant(merchantId);
        MapSqlParameterSource p = new MapSqlParameterSource("merchant", merchantId);
        Map<String, Object> merchant =
                first(
                        "SELECT m.id merchantId,m.account_number accountNumber,m.name merchantName,m.status merchantStatus,"
                                + "l.status lifecycleStatus,l.current_step_code currentStepCode,l.activated_at activatedAt "
                                + "FROM merchants m LEFT JOIN merchant_activation_lifecycles l ON l.merchant_id=m.id "
                                + "WHERE m.id=:merchant",
                        p);
        merchant.put(
                "packageAssignments",
                jdbc.queryForList(
                        "SELECT p.package_code AS packageCode,p.package_name AS packageName,a.environment,a.status,"
                                + "a.effective_from AS effectiveFrom,a.effective_to AS effectiveTo,a.notes,a.updated_at AS updatedAt "
                                + "FROM merchant_commercial_package_assignments a JOIN cito_commercial_packages p ON p.id=a.package_id "
                                + "WHERE a.merchant_id=:merchant ORDER BY a.updated_at DESC",
                        p));
        merchant.put(
                "activeEntitlements",
                jdbc.queryForList(
                        "SELECT e.service_code AS serviceCode,e.environment,e.plan_code AS planCode "
                                + "FROM cito_organizations o JOIN cito_service_entitlements e ON e.organization_id=o.id "
                                + "WHERE o.merchant_id=:merchant AND e.status='ACTIVE' "
                                + "AND (e.starts_at IS NULL OR e.starts_at<=CURRENT_TIMESTAMP) "
                                + "AND (e.ends_at IS NULL OR e.ends_at>CURRENT_TIMESTAMP) "
                                + "ORDER BY e.environment,e.service_code",
                        p));
        return merchant;
    }

    @Transactional
    public Map<String, Object> enrollFounding20(
            long merchantId,
            int slot,
            String commercialOwner,
            String customerSuccessOwner,
            Instant targetGoLiveAt,
            String notes,
            String actor) {
        requireMerchant(merchantId);
        if (slot < 1 || slot > 20) {
            throw new PaymentGatewayException("Founding 20 cohort slot must be between 1 and 20");
        }
        long occupied =
                count(
                        "SELECT COUNT(*) FROM founding20_merchants WHERE programme_status<>'EXITED'",
                        new MapSqlParameterSource());
        long alreadyEnrolled =
                count(
                        "SELECT COUNT(*) FROM founding20_merchants WHERE merchant_id=:merchant",
                        new MapSqlParameterSource("merchant", merchantId));
        if (alreadyEnrolled == 0 && occupied >= 20) {
            throw new PaymentGatewayException("Founding 20 cohort already has twenty active slots");
        }
        jdbc.update(
                "INSERT INTO founding20_merchants "
                        + "(merchant_id,cohort_slot,programme_status,commercial_owner,customer_success_owner,target_go_live_at,notes,created_by) "
                        + "VALUES (:merchant,:slot,'CANDIDATE',:commercialOwner,:customerSuccessOwner,:targetGoLive,:notes,:actor) "
                        + "ON DUPLICATE KEY UPDATE cohort_slot=VALUES(cohort_slot),commercial_owner=VALUES(commercial_owner),"
                        + "customer_success_owner=VALUES(customer_success_owner),target_go_live_at=VALUES(target_go_live_at),"
                        + "notes=VALUES(notes),updated_at=CURRENT_TIMESTAMP",
                new MapSqlParameterSource("merchant", merchantId)
                        .addValue("slot", slot)
                        .addValue("commercialOwner", blankToNull(commercialOwner))
                        .addValue("customerSuccessOwner", blankToNull(customerSuccessOwner))
                        .addValue("targetGoLive", targetGoLiveAt == null ? null : Timestamp.from(targetGoLiveAt))
                        .addValue("notes", blankToNull(notes))
                        .addValue("actor", blankToNull(actor)));
        return founding20Entry(merchantId);
    }

    @Transactional
    public Map<String, Object> updateFounding20Status(
            long merchantId, String status, String exitReason) {
        String normalized = oneOf(status, FOUNDING_STATUSES, "programmeStatus");
        int updated =
                jdbc.update(
                        "UPDATE founding20_merchants SET programme_status=:status,"
                                + "exited_at=CASE WHEN :status='EXITED' THEN CURRENT_TIMESTAMP ELSE exited_at END,"
                                + "exit_reason=CASE WHEN :status='EXITED' THEN :reason ELSE exit_reason END "
                                + "WHERE merchant_id=:merchant",
                        new MapSqlParameterSource("merchant", merchantId)
                                .addValue("status", normalized)
                                .addValue("reason", blankToNull(exitReason)));
        if (updated == 0) {
            throw new PaymentGatewayException("Merchant is not enrolled in Founding 20");
        }
        return founding20Entry(merchantId);
    }

    public List<Map<String, Object>> founding20() {
        return jdbc.queryForList(
                "SELECT f.cohort_slot AS cohortSlot,f.merchant_id AS merchantId,m.account_number AS accountNumber,m.name AS merchantName,"
                        + "f.programme_status AS programmeStatus,f.commercial_owner AS commercialOwner,"
                        + "f.customer_success_owner AS customerSuccessOwner,f.target_go_live_at AS targetGoLiveAt,"
                        + "l.status AS lifecycleStatus,l.current_step_code AS currentStepCode,l.activated_at AS activatedAt,"
                        + "f.enrolled_at AS enrolledAt,f.updated_at AS updatedAt "
                        + "FROM founding20_merchants f JOIN merchants m ON m.id=f.merchant_id "
                        + "LEFT JOIN merchant_activation_lifecycles l ON l.merchant_id=f.merchant_id "
                        + "ORDER BY f.cohort_slot",
                new MapSqlParameterSource());
    }

    @Transactional
    public Map<String, Object> enrollEmbeddedPartner(
            long merchantId,
            String programmeTier,
            String commercialOwner,
            Integer targetDownstreamMerchants,
            Instant targetGoLiveAt,
            String notes,
            String actor) {
        requireMerchant(merchantId);
        if (targetDownstreamMerchants != null && targetDownstreamMerchants < 0) {
            throw new PaymentGatewayException("targetDownstreamMerchants cannot be negative");
        }
        Long partnerId =
                jdbc.queryForObject(
                        "SELECT MAX(id) FROM embedded_partners WHERE merchant_id=:merchant AND status='ACTIVE'",
                        new MapSqlParameterSource("merchant", merchantId),
                        Long.class);
        if (partnerId == null || partnerId <= 0) {
            throw new PaymentGatewayException(
                    "Merchant must first have an active Embedded Cito partner record");
        }
        jdbc.update(
                "INSERT INTO embedded_partner_programmes "
                        + "(embedded_partner_id,merchant_id,programme_status,programme_tier,commercial_owner,target_downstream_merchants,target_go_live_at,notes,created_by) "
                        + "VALUES (:partner,:merchant,'CANDIDATE',:tier,:owner,:target,:targetGoLive,:notes,:actor) "
                        + "ON DUPLICATE KEY UPDATE programme_tier=VALUES(programme_tier),commercial_owner=VALUES(commercial_owner),"
                        + "target_downstream_merchants=VALUES(target_downstream_merchants),target_go_live_at=VALUES(target_go_live_at),"
                        + "notes=VALUES(notes),updated_at=CURRENT_TIMESTAMP",
                new MapSqlParameterSource("partner", partnerId)
                        .addValue("merchant", merchantId)
                        .addValue("tier", blankToNull(programmeTier))
                        .addValue("owner", blankToNull(commercialOwner))
                        .addValue("target", targetDownstreamMerchants)
                        .addValue("targetGoLive", targetGoLiveAt == null ? null : Timestamp.from(targetGoLiveAt))
                        .addValue("notes", blankToNull(notes))
                        .addValue("actor", blankToNull(actor)));
        return embeddedPartnerEntry(merchantId);
    }

    @Transactional
    public Map<String, Object> updateEmbeddedPartnerStatus(long merchantId, String status) {
        String normalized = oneOf(status, PARTNER_STATUSES, "programmeStatus");
        int updated =
                jdbc.update(
                        "UPDATE embedded_partner_programmes SET programme_status=:status WHERE merchant_id=:merchant",
                        new MapSqlParameterSource("merchant", merchantId).addValue("status", normalized));
        if (updated == 0) {
            throw new PaymentGatewayException("Merchant is not enrolled in the embedded partner programme");
        }
        return embeddedPartnerEntry(merchantId);
    }

    public List<Map<String, Object>> embeddedPartners() {
        return jdbc.queryForList(
                "SELECT ep.merchant_id AS merchantId,m.name AS merchantName,p.partner_reference AS partnerReference,"
                        + "ep.programme_status AS programmeStatus,ep.programme_tier AS programmeTier,ep.commercial_owner AS commercialOwner,"
                        + "ep.target_downstream_merchants AS targetDownstreamMerchants,"
                        + "COUNT(DISTINCT rel.downstream_merchant_id) AS activeDownstreamMerchants,"
                        + "ep.target_go_live_at AS targetGoLiveAt,ep.created_at AS enrolledAt,ep.updated_at AS updatedAt "
                        + "FROM embedded_partner_programmes ep JOIN embedded_partners p ON p.id=ep.embedded_partner_id "
                        + "JOIN merchants m ON m.id=ep.merchant_id "
                        + "LEFT JOIN embedded_partner_merchants rel ON rel.partner_id=p.id AND rel.status='ACTIVE' "
                        + "GROUP BY ep.id,ep.merchant_id,m.name,p.partner_reference,ep.programme_status,ep.programme_tier,"
                        + "ep.commercial_owner,ep.target_downstream_merchants,ep.target_go_live_at,ep.created_at,ep.updated_at "
                        + "ORDER BY ep.updated_at DESC",
                new MapSqlParameterSource());
    }

    private Map<String, Object> founding20Entry(long merchantId) {
        return first(
                "SELECT f.cohort_slot AS cohortSlot,f.merchant_id AS merchantId,m.name AS merchantName,"
                        + "f.programme_status AS programmeStatus,f.commercial_owner AS commercialOwner,"
                        + "f.customer_success_owner AS customerSuccessOwner,f.target_go_live_at AS targetGoLiveAt,"
                        + "l.status AS lifecycleStatus,l.current_step_code AS currentStepCode,l.activated_at AS activatedAt,"
                        + "f.notes,f.enrolled_at AS enrolledAt,f.updated_at AS updatedAt "
                        + "FROM founding20_merchants f JOIN merchants m ON m.id=f.merchant_id "
                        + "LEFT JOIN merchant_activation_lifecycles l ON l.merchant_id=f.merchant_id WHERE f.merchant_id=:merchant",
                new MapSqlParameterSource("merchant", merchantId));
    }

    private Map<String, Object> embeddedPartnerEntry(long merchantId) {
        return first(
                "SELECT ep.merchant_id AS merchantId,m.name AS merchantName,p.partner_reference AS partnerReference,"
                        + "ep.programme_status AS programmeStatus,ep.programme_tier AS programmeTier,ep.commercial_owner AS commercialOwner,"
                        + "ep.target_downstream_merchants AS targetDownstreamMerchants,ep.target_go_live_at AS targetGoLiveAt,"
                        + "ep.notes,ep.created_at AS enrolledAt,ep.updated_at AS updatedAt "
                        + "FROM embedded_partner_programmes ep JOIN embedded_partners p ON p.id=ep.embedded_partner_id "
                        + "JOIN merchants m ON m.id=ep.merchant_id WHERE ep.merchant_id=:merchant",
                new MapSqlParameterSource("merchant", merchantId));
    }

    private Map<String, Object> transitionPackage(
            String packageCode, String expected, String target, String actor, boolean approve) {
        String code = required(packageCode, "packageCode").toUpperCase(Locale.ROOT);
        if (!PACKAGE_STATUSES.contains(target)) {
            throw new PaymentGatewayException("Unsupported package status");
        }
        int updated =
                jdbc.update(
                        "UPDATE cito_commercial_packages SET status=:target WHERE package_code=:code AND status=:expected",
                        new MapSqlParameterSource("code", code)
                                .addValue("expected", expected)
                                .addValue("target", target)
                                .addValue("actor", blankToNull(actor)));
        if (updated == 0) {
            throw new PaymentGatewayException(
                    "Package cannot transition from its current state to " + target);
        }
        return packageByCode(code);
    }

    private Map<String, Object> packageByCode(String packageCode) {
        Map<String, Object> result =
                first(
                        "SELECT package_code AS packageCode,package_name AS packageName,target_segment AS targetSegment,"
                                + "description,service_codes_json AS serviceCodes,onboarding_support_level AS onboardingSupportLevel,"
                                + "commercial_terms_json AS commercialTerms,status,version_number AS versionNumber,"
                                + "created_by AS createdBy,approved_by AS approvedBy,approved_at AS approvedAt,updated_at AS updatedAt "
                                + "FROM cito_commercial_packages WHERE package_code=:code",
                        new MapSqlParameterSource("code", packageCode));
        if (result.isEmpty()) {
            throw new PaymentGatewayException("Commercial package was not found");
        }
        return result;
    }

    private long activePackageId(String packageCode) {
        String code = required(packageCode, "packageCode").toUpperCase(Locale.ROOT);
        Long id =
                jdbc.queryForObject(
                        "SELECT MAX(id) FROM cito_commercial_packages WHERE package_code=:code AND status='ACTIVE'",
                        new MapSqlParameterSource("code", code),
                        Long.class);
        if (id == null || id <= 0) {
            throw new PaymentGatewayException("Commercial package must be ACTIVE before assignment");
        }
        return id;
    }

    private void requireMerchant(long merchantId) {
        if (merchantId <= 0) {
            throw new PaymentGatewayException("merchantId must be positive");
        }
        if (count(
                        "SELECT COUNT(*) FROM merchants WHERE id=:merchant",
                        new MapSqlParameterSource("merchant", merchantId))
                == 0) {
            throw new PaymentGatewayException("Merchant was not found");
        }
    }

    private long count(String sql, MapSqlParameterSource p) {
        Long result = jdbc.queryForObject(sql, p, Long.class);
        return result == null ? 0L : result;
    }

    private Map<String, Object> first(String sql, MapSqlParameterSource p) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, p);
        return rows.isEmpty() ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(rows.get(0));
    }

    private String normalizeEnvironment(String value) {
        String normalized = normalized(value, "PRODUCTION");
        if (!Set.of("SANDBOX", "PRODUCTION").contains(normalized)) {
            throw new PaymentGatewayException("environment must be SANDBOX or PRODUCTION");
        }
        return normalized;
    }

    private String oneOf(String value, Set<String> allowed, String field) {
        String normalized = required(value, field).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new PaymentGatewayException(field + " is unsupported");
        }
        return normalized;
    }

    private String requiredJsonArray(String value, String field) {
        String json = required(value, field).trim();
        if (!json.startsWith("[") || !json.endsWith("]")) {
            throw new PaymentGatewayException(field + " must be a JSON array");
        }
        return json;
    }

    private String jsonOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String json = value.trim();
        if (!(json.startsWith("{") && json.endsWith("}"))) {
            throw new PaymentGatewayException("commercialTermsJson must be a JSON object");
        }
        return json;
    }

    private String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new PaymentGatewayException(field + " is required");
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
