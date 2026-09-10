package net.citotech.cito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * P2 compliance, KYB and KYC foundation endpoints.
 *
 * <p>These endpoints intentionally expose a thin, auditable workflow surface over the Flyway-backed
 * compliance schema. Existing admin/session filters remain responsible for authentication and
 * authorization for /api/v2/admin/** routes.
 */
@RestController
@RequestMapping(
        path = "/api/v2",
        produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
public class ComplianceKybKycController {

    private final JdbcTemplate jdbc;

    public ComplianceKybKycController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/admin/kyb/profiles")
    public ResponseEntity<Map<String, Object>> listKybProfiles(
            @RequestParam(required = false) String status) {
        String sql =
                "select id, merchant_id, merchant_number, registered_business_name, trading_name, country_code, "
                        + "risk_rating, status, submitted_at, reviewed_at, reviewed_by, expires_at "
                        + "from kyb_profiles ";
        Object[] args = new Object[] {};
        if (status != null && !status.isBlank()) {
            sql += "where status = ? ";
            args = new Object[] {status};
        }
        sql += "order by updated_at desc limit 200";
        return ResponseEntity.ok(ok("profiles", jdbc.queryForList(sql, args)));
    }

    @PostMapping("/admin/kyb/profiles")
    public ResponseEntity<Map<String, Object>> createOrUpdateKybProfile(
            @RequestBody Map<String, Object> body) {
        require(body, "merchantId");
        require(body, "merchantNumber");
        require(body, "registeredBusinessName");
        require(body, "countryCode");

        jdbc.update(
                "insert into kyb_profiles (merchant_id, merchant_number, registered_business_name, trading_name, "
                        + "registration_number, tax_identification_number, business_type, country_code, physical_address, "
                        + "expected_monthly_volume, expected_monthly_payout_volume, primary_use_case, risk_rating, status, submitted_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp) "
                        + "on duplicate key update registered_business_name = values(registered_business_name), "
                        + "trading_name = values(trading_name), registration_number = values(registration_number), "
                        + "tax_identification_number = values(tax_identification_number), business_type = values(business_type), "
                        + "country_code = values(country_code), physical_address = values(physical_address), "
                        + "expected_monthly_volume = values(expected_monthly_volume), "
                        + "expected_monthly_payout_volume = values(expected_monthly_payout_volume), "
                        + "primary_use_case = values(primary_use_case), risk_rating = values(risk_rating), "
                        + "status = values(status), submitted_at = current_timestamp",
                body.get("merchantId"),
                body.get("merchantNumber"),
                body.get("registeredBusinessName"),
                body.get("tradingName"),
                body.get("registrationNumber"),
                body.get("taxIdentificationNumber"),
                body.get("businessType"),
                body.get("countryCode"),
                body.get("physicalAddress"),
                body.get("expectedMonthlyVolume"),
                body.get("expectedMonthlyPayoutVolume"),
                body.get("primaryUseCase"),
                valueOr(body, "riskRating", "UNRATED"),
                valueOr(body, "status", "SUBMITTED"));

        return ResponseEntity.ok(ok("merchantNumber", body.get("merchantNumber")));
    }

    @PostMapping("/admin/kyb/profiles/{merchantNumber}/decision")
    public ResponseEntity<Map<String, Object>> decideKybProfile(
            @PathVariable String merchantNumber, @RequestBody Map<String, Object> body) {
        require(body, "status");
        jdbc.update(
                "update kyb_profiles set status = ?, reviewed_by = ?, reviewed_at = current_timestamp, review_reason = ? "
                        + "where merchant_number = ?",
                body.get("status"),
                body.get("reviewedBy"),
                body.get("reason"),
                merchantNumber);
        return ResponseEntity.ok(
                ok("merchantNumber", merchantNumber, "status", body.get("status")));
    }

    @GetMapping("/admin/compliance/cases")
    public ResponseEntity<Map<String, Object>> listComplianceCases(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String subjectReference) {
        String sql =
                "select id, case_reference, case_type, severity, case_status as status, "
                        + "entity_type as subject_type, cast(entity_id as char) as subject_reference, "
                        + "null as merchant_id, null as merchant_number, source_reference as transaction_reference, "
                        + "null as title, null as hold_scope, false as hold_active, assigned_to, "
                        + "created_at as opened_at, null as due_at, closed_at, decision "
                        + "from compliance_cases where 1=1 ";
        if (status != null
                && !status.isBlank()
                && subjectReference != null
                && !subjectReference.isBlank()) {
            sql +=
                    "and case_status = ? and cast(entity_id as char) = ? order by created_at desc limit 200";
            return ResponseEntity.ok(ok("cases", jdbc.queryForList(sql, status, subjectReference)));
        }
        if (status != null && !status.isBlank()) {
            sql += "and case_status = ? order by created_at desc limit 200";
            return ResponseEntity.ok(ok("cases", jdbc.queryForList(sql, status)));
        }
        if (subjectReference != null && !subjectReference.isBlank()) {
            sql += "and cast(entity_id as char) = ? order by created_at desc limit 200";
            return ResponseEntity.ok(ok("cases", jdbc.queryForList(sql, subjectReference)));
        }
        sql += "order by created_at desc limit 200";
        return ResponseEntity.ok(ok("cases", jdbc.queryForList(sql)));
    }

    @GetMapping("/admin/compliance/cases/{caseReference}")
    public ResponseEntity<Map<String, Object>> getComplianceCase(
            @PathVariable String caseReference) {
        Map<String, Object> complianceCase =
                jdbc.queryForMap(
                        "select * from compliance_cases where case_reference = ?", caseReference);
        List<Map<String, Object>> events =
                jdbc.queryForList(
                        "select event_type, actor, from_status, to_status, notes, evidence_reference, created_at "
                                + "from compliance_case_events where case_id = ? order by created_at asc",
                        complianceCase.get("id"));
        Map<String, Object> response = ok("case", complianceCase);
        response.put("events", events);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/admin/compliance/cases")
    public ResponseEntity<Map<String, Object>> createComplianceCase(
            @RequestBody Map<String, Object> body) {
        require(body, "caseType");
        require(body, "subjectType");
        require(body, "subjectReference");
        require(body, "title");
        String caseReference = "CASE-" + shortUuid();
        String status = asString(valueOr(body, "status", "OPEN"));
        jdbc.update(
                "insert into compliance_cases (case_reference, case_type, severity, status, subject_type, "
                        + "subject_reference, merchant_id, merchant_number, transaction_reference, related_resource_type, "
                        + "related_resource_id, title, summary, hold_scope, hold_active, assigned_to, opened_by, due_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                caseReference,
                body.get("caseType"),
                valueOr(body, "severity", "MEDIUM"),
                status,
                body.get("subjectType"),
                body.get("subjectReference"),
                body.get("merchantId"),
                body.get("merchantNumber"),
                body.get("transactionReference"),
                body.get("relatedResourceType"),
                body.get("relatedResourceId"),
                body.get("title"),
                body.get("summary"),
                body.get("holdScope"),
                booleanValue(body.get("holdActive")),
                body.get("assignedTo"),
                body.get("openedBy"),
                body.get("dueAt"));
        Long caseId =
                jdbc.queryForObject(
                        "select id from compliance_cases where case_reference = ?",
                        Long.class,
                        caseReference);
        insertCaseEvent(
                caseId,
                "CASE_CREATED",
                asString(body.get("openedBy")),
                null,
                status,
                asString(body.get("summary")),
                asString(body.get("evidenceReference")));
        return ResponseEntity.ok(ok("caseReference", caseReference, "status", status));
    }

    @PostMapping("/admin/compliance/cases/{caseReference}/decision")
    public ResponseEntity<Map<String, Object>> decideComplianceCase(
            @PathVariable String caseReference, @RequestBody Map<String, Object> body) {
        require(body, "decision");
        require(body, "status");
        Map<String, Object> current =
                jdbc.queryForMap(
                        "select id, status from compliance_cases where case_reference = ?",
                        caseReference);
        jdbc.update(
                "update compliance_cases set status = ?, decision = ?, decision_reason = ?, hold_active = ?, "
                        + "closed_by = ?, closed_at = case when ? in ('APPROVED','REJECTED','CLOSED') then current_timestamp else closed_at end "
                        + "where case_reference = ?",
                body.get("status"),
                body.get("decision"),
                body.get("reason"),
                booleanValue(body.get("holdActive")),
                body.get("decidedBy"),
                body.get("status"),
                caseReference);
        insertCaseEvent(
                ((Number) current.get("id")).longValue(),
                "CASE_DECISION",
                asString(body.get("decidedBy")),
                asString(current.get("status")),
                asString(body.get("status")),
                asString(body.get("reason")),
                asString(body.get("evidenceReference")));
        return ResponseEntity.ok(
                ok(
                        "caseReference",
                        caseReference,
                        "status",
                        body.get("status"),
                        "decision",
                        body.get("decision")));
    }

    @PostMapping("/admin/compliance/screening-results")
    public ResponseEntity<Map<String, Object>> recordScreeningResult(
            @RequestBody Map<String, Object> body) {
        require(body, "providerCode");
        require(body, "screeningType");
        require(body, "subjectType");
        require(body, "subjectReference");
        String screeningReference = "SCR-" + shortUuid();
        Long caseId = lookupCaseId(asString(body.get("caseReference")));
        jdbc.update(
                "insert into compliance_screening_results (screening_reference, provider_code, screening_type, "
                        + "subject_type, subject_reference, risk_score, result_status, match_count, raw_result_reference, "
                        + "case_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                screeningReference,
                body.get("providerCode"),
                body.get("screeningType"),
                body.get("subjectType"),
                body.get("subjectReference"),
                body.get("riskScore"),
                valueOr(body, "resultStatus", "PENDING"),
                valueOr(body, "matchCount", 0),
                body.get("rawResultReference"),
                caseId);
        return ResponseEntity.ok(ok("screeningReference", screeningReference));
    }

    @PostMapping("/admin/compliance/monitoring-alerts")
    public ResponseEntity<Map<String, Object>> createMonitoringAlert(
            @RequestBody Map<String, Object> body) {
        require(body, "ruleCode");
        require(body, "subjectType");
        require(body, "subjectReference");
        String alertReference = "ALERT-" + shortUuid();
        Long ruleId =
                jdbc.queryForObject(
                        "select id from transaction_monitoring_rules where rule_code = ?",
                        Long.class,
                        body.get("ruleCode"));
        Long caseId = lookupCaseId(asString(body.get("caseReference")));
        jdbc.update(
                "insert into transaction_monitoring_alerts (alert_reference, rule_id, severity, status, "
                        + "subject_type, subject_reference, merchant_id, transaction_reference, case_id, assigned_to) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                alertReference,
                ruleId,
                valueOr(body, "severity", "MEDIUM"),
                valueOr(body, "status", "OPEN"),
                body.get("subjectType"),
                body.get("subjectReference"),
                body.get("merchantId"),
                body.get("transactionReference"),
                caseId,
                body.get("assignedTo"));
        return ResponseEntity.ok(ok("alertReference", alertReference));
    }

    @PostMapping("/admin/regulatory/evidence-exports")
    public ResponseEntity<Map<String, Object>> requestRegulatoryEvidenceExport(
            @RequestBody Map<String, Object> body) {
        require(body, "exportType");
        require(body, "jurisdiction");
        require(body, "periodStart");
        require(body, "periodEnd");
        String exportReference = "REG-" + shortUuid();
        jdbc.update(
                "insert into regulatory_evidence_exports (export_reference, export_type, jurisdiction, "
                        + "period_start, period_end, status, requested_by) values (?, ?, ?, ?, ?, ?, ?)",
                exportReference,
                body.get("exportType"),
                body.get("jurisdiction"),
                body.get("periodStart"),
                body.get("periodEnd"),
                valueOr(body, "status", "REQUESTED"),
                body.get("requestedBy"));
        return ResponseEntity.ok(
                ok(
                        "exportReference",
                        exportReference,
                        "status",
                        valueOr(body, "status", "REQUESTED")));
    }

    private Long lookupCaseId(String caseReference) {
        if (caseReference == null || caseReference.isBlank()) {
            return null;
        }
        return jdbc.queryForObject(
                "select id from compliance_cases where case_reference = ?",
                Long.class,
                caseReference);
    }

    private void insertCaseEvent(
            Long caseId,
            String eventType,
            String actor,
            String fromStatus,
            String toStatus,
            String notes,
            String evidenceReference) {
        jdbc.update(
                "insert into compliance_case_events (case_id, event_type, actor, from_status, to_status, notes, "
                        + "evidence_reference) values (?, ?, ?, ?, ?, ?, ?)",
                caseId,
                eventType,
                actor,
                fromStatus,
                toStatus,
                notes,
                evidenceReference);
    }

    private static void require(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
    }

    private static Object valueOr(Map<String, Object> body, String key, Object fallback) {
        Object value = body.get(key);
        return value == null ? fallback : value;
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean booleanValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static Map<String, Object> ok(Object... pairs) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "OK");
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            response.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return response;
    }
}
