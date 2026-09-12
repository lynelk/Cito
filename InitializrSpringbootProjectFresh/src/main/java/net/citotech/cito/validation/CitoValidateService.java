package net.citotech.cito.validation;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.platform.kernel.PlatformEntitlementGateway;
import net.citotech.cito.platform.kernel.PlatformUsageContract;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CitoValidateService {
    static final String SERVICE_CODE = "CITO_VALIDATE";
    static final String ENGINE_VERSION = "1.0.0";

    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformEntitlementGateway entitlements;
    private final PlatformUsageContract usage;

    public CitoValidateService(
            NamedParameterJdbcTemplate jdbc,
            PlatformEntitlementGateway entitlements,
            PlatformUsageContract usage) {
        this.jdbc = jdbc;
        this.entitlements = entitlements;
        this.usage = usage;
    }

    public List<Map<String, Object>> availableRulePacks() {
        return jdbc.queryForList(
                "SELECT p.pack_code packCode,p.pack_name packName,p.jurisdiction,p.authority_name authorityName,"
                        + "p.dataset_type datasetType,p.status,p.content_status contentStatus,"
                        + "v.version_number versionNumber,v.engine_version engineVersion,v.effective_from effectiveFrom "
                        + "FROM validation_rule_packs p JOIN validation_rule_pack_versions v ON v.rule_pack_id=p.id "
                        + "WHERE p.status='ACTIVE' AND v.status='ACTIVE' "
                        + "AND (v.effective_from IS NULL OR v.effective_from<=CURRENT_TIMESTAMP) "
                        + "AND (v.effective_to IS NULL OR v.effective_to>CURRENT_TIMESTAMP) "
                        + "ORDER BY p.jurisdiction,p.pack_name,v.created_at DESC",
                new MapSqlParameterSource());
    }

    public List<Map<String, Object>> allRulePacks() {
        return jdbc.queryForList(
                "SELECT p.pack_code packCode,p.pack_name packName,p.jurisdiction,p.authority_name authorityName,"
                        + "p.dataset_type datasetType,p.status,p.content_status contentStatus,"
                        + "v.version_number versionNumber,v.status versionStatus,v.engine_version engineVersion,"
                        + "v.created_by createdBy,v.approved_by approvedBy,v.approved_at approvedAt "
                        + "FROM validation_rule_packs p LEFT JOIN validation_rule_pack_versions v ON v.rule_pack_id=p.id "
                        + "ORDER BY p.pack_code,v.created_at DESC",
                new MapSqlParameterSource());
    }

    @Transactional
    public Map<String, Object> createRulePackVersion(
            String packCode, String versionNumber, String rulesJson, String actor) {
        long packId = packId(packCode);
        JSONObject rules = parseRules(rulesJson);
        int inserted =
                jdbc.update(
                        "INSERT INTO validation_rule_pack_versions "
                                + "(rule_pack_id,version_number,rules_json,engine_version,status,created_by) "
                                + "VALUES (:pack_id,:version_number,:rules_json,:engine_version,'DRAFT',:created_by)",
                        new MapSqlParameterSource()
                                .addValue("pack_id", packId)
                                .addValue("version_number", required(versionNumber, "versionNumber"))
                                .addValue("rules_json", rules.toString())
                                .addValue("engine_version", ENGINE_VERSION)
                                .addValue("created_by", actor));
        return Map.of(
                "created", inserted == 1,
                "packCode", normalized(packCode),
                "versionNumber", versionNumber,
                "status", "DRAFT");
    }

    @Transactional
    public Map<String, Object> approveRulePackVersion(
            String packCode, String versionNumber, String actor) {
        long packId = packId(packCode);
        int updated =
                jdbc.update(
                        "UPDATE validation_rule_pack_versions SET status='ACTIVE',approved_by=:approved_by,"
                                + "approved_at=CURRENT_TIMESTAMP,effective_from=COALESCE(effective_from,CURRENT_TIMESTAMP) "
                                + "WHERE rule_pack_id=:pack_id AND version_number=:version_number AND status='DRAFT' "
                                + "AND (created_by IS NULL OR created_by<>:approved_by)",
                        new MapSqlParameterSource()
                                .addValue("pack_id", packId)
                                .addValue("version_number", required(versionNumber, "versionNumber"))
                                .addValue("approved_by", actor));
        if (updated == 0) {
            throw new PaymentGatewayException(
                    "Rule-pack version was not found, was already approved, or violates maker-checker");
        }
        jdbc.update(
                "UPDATE validation_rule_pack_versions SET status='RETIRED',effective_to=CURRENT_TIMESTAMP "
                        + "WHERE rule_pack_id=:pack_id AND version_number<>:version_number AND status='ACTIVE'",
                new MapSqlParameterSource()
                        .addValue("pack_id", packId)
                        .addValue("version_number", versionNumber));
        jdbc.update(
                "UPDATE validation_rule_packs SET status='ACTIVE',content_status='APPROVED' WHERE id=:pack_id",
                new MapSqlParameterSource("pack_id", packId));
        return Map.of(
                "packCode", normalized(packCode),
                "versionNumber", versionNumber,
                "status", "ACTIVE");
    }

    @Transactional
    public Map<String, Object> createJob(
            long merchantId,
            String environment,
            String packCode,
            String versionNumber,
            String mappingCode,
            String mappingVersion,
            String sourceReference,
            String sourcePayload,
            String idempotencyKey,
            String actor) {
        String env = environment(environment);
        entitlements.requireEntitlement(merchantId, SERVICE_CODE, env);
        JSONArray records = parseRecords(sourcePayload);
        String payload = records.toString();
        String hash = sha256(payload);
        Map<String, Object> existing = existingJob(merchantId, idempotencyKey);
        if (existing != null) {
            if (!hash.equals(existing.get("sourceSha256"))) {
                throw new PaymentGatewayException(
                        "Idempotency key already exists for a different validation dataset");
            }
            return existing;
        }
        long ruleVersionId = activeRuleVersion(packCode, versionNumber);
        Long mappingVersionId = mappingVersionId(merchantId, mappingCode, mappingVersion);
        String reference = reference("VAL");
        jdbc.update(
                "INSERT INTO validation_jobs "
                        + "(merchant_id,job_reference,rule_pack_version_id,mapping_version_id,source_reference,"
                        + "source_sha256,source_payload,engine_version,idempotency_key,status,total_records,requested_by) "
                        + "VALUES (:merchant_id,:job_reference,:rule_version_id,:mapping_version_id,:source_reference,"
                        + ":source_sha256,:source_payload,:engine_version,:idempotency_key,'QUEUED',:total_records,:requested_by)",
                new MapSqlParameterSource()
                        .addValue("merchant_id", merchantId)
                        .addValue("job_reference", reference)
                        .addValue("rule_version_id", ruleVersionId)
                        .addValue("mapping_version_id", mappingVersionId)
                        .addValue("source_reference", required(sourceReference, "sourceReference"))
                        .addValue("source_sha256", hash)
                        .addValue("source_payload", payload)
                        .addValue("engine_version", ENGINE_VERSION)
                        .addValue("idempotency_key", required(idempotencyKey, "idempotencyKey"))
                        .addValue("total_records", records.length())
                        .addValue("requested_by", actor));
        return job(merchantId, reference);
    }

    public List<Map<String, Object>> jobs(long merchantId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return jdbc.queryForList(
                "SELECT j.job_reference jobReference,p.pack_code packCode,v.version_number versionNumber,"
                        + "j.source_reference sourceReference,j.source_sha256 sourceSha256,j.engine_version engineVersion,"
                        + "j.status,j.progress_percent progressPercent,j.total_records totalRecords,j.valid_records validRecords,"
                        + "j.error_records errorRecords,j.warning_records warningRecords,j.requested_at requestedAt,"
                        + "j.started_at startedAt,j.completed_at completedAt,j.failure_code failureCode "
                        + "FROM validation_jobs j JOIN validation_rule_pack_versions v ON v.id=j.rule_pack_version_id "
                        + "JOIN validation_rule_packs p ON p.id=v.rule_pack_id WHERE j.merchant_id=:merchant_id "
                        + "ORDER BY j.id DESC LIMIT "
                        + safeLimit,
                new MapSqlParameterSource("merchant_id", merchantId));
    }

    public Map<String, Object> job(long merchantId, String reference) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT j.job_reference jobReference,p.pack_code packCode,v.version_number versionNumber,"
                                + "j.source_reference sourceReference,j.source_sha256 sourceSha256,j.engine_version engineVersion,"
                                + "j.status,j.progress_percent progressPercent,j.total_records totalRecords,j.valid_records validRecords,"
                                + "j.error_records errorRecords,j.warning_records warningRecords,j.requested_at requestedAt,"
                                + "j.started_at startedAt,j.completed_at completedAt,j.failure_code failureCode,j.failure_detail failureDetail "
                                + "FROM validation_jobs j JOIN validation_rule_pack_versions v ON v.id=j.rule_pack_version_id "
                                + "JOIN validation_rule_packs p ON p.id=v.rule_pack_id "
                                + "WHERE j.merchant_id=:merchant_id AND j.job_reference=:reference",
                        new MapSqlParameterSource()
                                .addValue("merchant_id", merchantId)
                                .addValue("reference", required(reference, "jobReference")));
        if (rows.isEmpty()) {
            throw new PaymentGatewayException("Validation job was not found");
        }
        return rows.get(0);
    }

    public List<Map<String, Object>> findings(long merchantId, String reference, int limit) {
        long jobId = jobId(merchantId, reference);
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return jdbc.queryForList(
                "SELECT record_number recordNumber,field_name fieldName,rule_code ruleCode,severity,category,message,"
                        + "remediation,status,created_at createdAt FROM validation_findings "
                        + "WHERE job_id=:job_id ORDER BY id LIMIT "
                        + safeLimit,
                new MapSqlParameterSource("job_id", jobId));
    }

    @Scheduled(fixedDelayString = "${cito.validation.worker-delay-ms:5000}")
    @SchedulerLock(name = "citoValidateJobs", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1S")
    public void processQueuedJobs() {
        List<Long> ids =
                jdbc.query(
                        "SELECT id FROM validation_jobs WHERE status='QUEUED' ORDER BY id LIMIT 20",
                        new MapSqlParameterSource(),
                        (rs, rowNum) -> rs.getLong("id"));
        for (Long id : ids) {
            processJob(id);
        }
    }

    @Transactional
    void processJob(long jobId) {
        int claimed =
                jdbc.update(
                        "UPDATE validation_jobs SET status='RUNNING',progress_percent=1,started_at=CURRENT_TIMESTAMP "
                                + "WHERE id=:id AND status='QUEUED'",
                        new MapSqlParameterSource("id", jobId));
        if (claimed == 0) {
            return;
        }
        JobContext context = jobContext(jobId);
        try {
            JSONArray records = new JSONArray(context.sourcePayload());
            JSONObject ruleDocument = new JSONObject(context.rulesJson());
            JSONArray rules = ruleDocument.optJSONArray("rules");
            if (rules == null) {
                rules = new JSONArray();
            }
            long errorRecords = 0;
            long warningRecords = 0;
            long validRecords = 0;
            for (int index = 0; index < records.length(); index++) {
                JSONObject record = records.optJSONObject(index);
                if (record == null) {
                    addFinding(jobId, index + 1L, null, "DATASET_OBJECT_REQUIRED", "ERROR", "SCHEMA", "Record must be a JSON object", null);
                    errorRecords++;
                    continue;
                }
                boolean error = false;
                boolean warning = false;
                for (int r = 0; r < rules.length(); r++) {
                    JSONObject rule = rules.optJSONObject(r);
                    if (rule == null) {
                        continue;
                    }
                    Finding finding = evaluate(record, rule);
                    if (finding != null) {
                        addFinding(jobId, index + 1L, finding.field(), finding.code(), finding.severity(), finding.category(), finding.message(), finding.remediation());
                        error |= "ERROR".equals(finding.severity());
                        warning |= "WARNING".equals(finding.severity());
                    }
                }
                if (error) {
                    errorRecords++;
                } else {
                    validRecords++;
                }
                if (warning) {
                    warningRecords++;
                }
            }
            jdbc.update(
                    "UPDATE validation_jobs SET status=:status,progress_percent=100,valid_records=:valid_records,"
                            + "error_records=:error_records,warning_records=:warning_records,completed_at=CURRENT_TIMESTAMP "
                            + "WHERE id=:id",
                    new MapSqlParameterSource()
                            .addValue("id", jobId)
                            .addValue("status", errorRecords > 0 ? "COMPLETED_WITH_ERRORS" : "COMPLETED")
                            .addValue("valid_records", validRecords)
                            .addValue("error_records", errorRecords)
                            .addValue("warning_records", warningRecords));
            usage.recordUsage(
                    context.merchantId(),
                    SERVICE_CODE,
                    "VALIDATION_RECORD",
                    Instant.now(),
                    BigDecimal.valueOf(records.length()),
                    null,
                    Map.of("packCode", context.packCode(), "version", context.versionNumber()),
                    context.jobReference(),
                    "validation:" + context.jobReference());
        } catch (RuntimeException e) {
            jdbc.update(
                    "UPDATE validation_jobs SET status='FAILED',failure_code='VALIDATION_ENGINE_ERROR',"
                            + "failure_detail=:detail,completed_at=CURRENT_TIMESTAMP WHERE id=:id",
                    new MapSqlParameterSource()
                            .addValue("id", jobId)
                            .addValue("detail", safe(e.getMessage())));
        }
    }

    private Finding evaluate(JSONObject record, JSONObject rule) {
        String type = normalized(rule.optString("type"));
        String field = rule.optString("field", "").trim();
        String code = required(rule.optString("code", "RULE"), "rule.code");
        String severity = normalized(rule.optString("severity", "ERROR"));
        String category = normalized(rule.optString("category", "DATA_QUALITY"));
        Object value = field.isEmpty() ? null : record.opt(field);
        boolean invalid = false;
        if ("REQUIRED".equals(type)) {
            invalid = value == null || value == JSONObject.NULL || String.valueOf(value).isBlank();
        } else if ("REGEX".equals(type) && value != null && value != JSONObject.NULL) {
            invalid = !Pattern.compile(rule.optString("pattern", ".*")).matcher(String.valueOf(value)).matches();
        } else if ("ENUM".equals(type) && value != null && value != JSONObject.NULL) {
            JSONArray allowed = rule.optJSONArray("allowed");
            invalid = allowed != null && !contains(allowed, String.valueOf(value));
        } else if ("MAX_LENGTH".equals(type) && value != null && value != JSONObject.NULL) {
            invalid = String.valueOf(value).length() > rule.optInt("max", Integer.MAX_VALUE);
        } else if ("MIN_LENGTH".equals(type) && value != null && value != JSONObject.NULL) {
            invalid = String.valueOf(value).length() < rule.optInt("min", 0);
        } else if ("NUMBER_RANGE".equals(type) && value != null && value != JSONObject.NULL) {
            try {
                double number = Double.parseDouble(String.valueOf(value));
                invalid = number < rule.optDouble("min", -Double.MAX_VALUE) || number > rule.optDouble("max", Double.MAX_VALUE);
            } catch (NumberFormatException e) {
                invalid = true;
            }
        }
        if (!invalid) {
            return null;
        }
        return new Finding(
                field.isEmpty() ? null : field,
                code,
                severity,
                category,
                rule.optString("message", "Validation rule failed"),
                blankToNull(rule.optString("remediation", "")));
    }

    private boolean contains(JSONArray values, String candidate) {
        for (int i = 0; i < values.length(); i++) {
            if (candidate.equals(String.valueOf(values.opt(i)))) {
                return true;
            }
        }
        return false;
    }

    private void addFinding(
            long jobId,
            long recordNumber,
            String field,
            String code,
            String severity,
            String category,
            String message,
            String remediation) {
        jdbc.update(
                "INSERT INTO validation_findings "
                        + "(job_id,record_number,field_name,rule_code,severity,category,message,remediation,status) "
                        + "VALUES (:job_id,:record_number,:field_name,:rule_code,:severity,:category,:message,:remediation,'OPEN')",
                new MapSqlParameterSource()
                        .addValue("job_id", jobId)
                        .addValue("record_number", recordNumber)
                        .addValue("field_name", field)
                        .addValue("rule_code", code)
                        .addValue("severity", severity)
                        .addValue("category", category)
                        .addValue("message", message)
                        .addValue("remediation", remediation));
    }

    private JSONObject parseRules(String value) {
        JSONObject document = new JSONObject(required(value, "rulesJson"));
        JSONArray rules = document.optJSONArray("rules");
        if (rules == null) {
            throw new PaymentGatewayException("rulesJson must contain a rules array");
        }
        return document;
    }

    private JSONArray parseRecords(String value) {
        try {
            JSONArray records = new JSONArray(required(value, "sourcePayload"));
            if (records.length() == 0) {
                throw new PaymentGatewayException("Validation dataset must contain at least one record");
            }
            if (records.length() > 10000) {
                throw new PaymentGatewayException("Inline validation datasets are limited to 10,000 records");
            }
            return records;
        } catch (PaymentGatewayException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PaymentGatewayException("sourcePayload must be a JSON array");
        }
    }

    private Map<String, Object> existingJob(long merchantId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT job_reference jobReference,source_sha256 sourceSha256,status,progress_percent progressPercent,"
                                + "total_records totalRecords,valid_records validRecords,error_records errorRecords,warning_records warningRecords "
                                + "FROM validation_jobs WHERE merchant_id=:merchant_id AND idempotency_key=:idempotency_key",
                        new MapSqlParameterSource()
                                .addValue("merchant_id", merchantId)
                                .addValue("idempotency_key", idempotencyKey));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long activeRuleVersion(String packCode, String versionNumber) {
        List<Long> rows =
                jdbc.query(
                        "SELECT v.id FROM validation_rule_packs p JOIN validation_rule_pack_versions v ON v.rule_pack_id=p.id "
                                + "WHERE p.pack_code=:pack_code AND p.status='ACTIVE' AND v.version_number=:version_number "
                                + "AND v.status='ACTIVE' AND (v.effective_from IS NULL OR v.effective_from<=CURRENT_TIMESTAMP) "
                                + "AND (v.effective_to IS NULL OR v.effective_to>CURRENT_TIMESTAMP)",
                        new MapSqlParameterSource()
                                .addValue("pack_code", normalized(packCode))
                                .addValue("version_number", required(versionNumber, "versionNumber")),
                        (rs, rowNum) -> rs.getLong("id"));
        if (rows.isEmpty()) {
            throw new PaymentGatewayException("Approved rule-pack version is not available");
        }
        return rows.get(0);
    }

    private long packId(String packCode) {
        List<Long> rows =
                jdbc.query(
                        "SELECT id FROM validation_rule_packs WHERE pack_code=:pack_code",
                        new MapSqlParameterSource("pack_code", normalized(packCode)),
                        (rs, rowNum) -> rs.getLong("id"));
        if (rows.isEmpty()) {
            throw new PaymentGatewayException("Validation rule pack was not found");
        }
        return rows.get(0);
    }

    private Long mappingVersionId(long merchantId, String mappingCode, String versionNumber) {
        if (mappingCode == null || mappingCode.isBlank()) {
            return null;
        }
        List<Long> rows =
                jdbc.query(
                        "SELECT v.id FROM validation_mapping_profiles p JOIN validation_mapping_versions v ON v.mapping_profile_id=p.id "
                                + "WHERE p.merchant_id=:merchant_id AND p.mapping_code=:mapping_code AND p.status='ACTIVE' "
                                + "AND v.version_number=:version_number",
                        new MapSqlParameterSource()
                                .addValue("merchant_id", merchantId)
                                .addValue("mapping_code", normalized(mappingCode))
                                .addValue("version_number", required(versionNumber, "mappingVersion")),
                        (rs, rowNum) -> rs.getLong("id"));
        if (rows.isEmpty()) {
            throw new PaymentGatewayException("Validation mapping version was not found");
        }
        return rows.get(0);
    }

    private long jobId(long merchantId, String reference) {
        List<Long> rows =
                jdbc.query(
                        "SELECT id FROM validation_jobs WHERE merchant_id=:merchant_id AND job_reference=:reference",
                        new MapSqlParameterSource()
                                .addValue("merchant_id", merchantId)
                                .addValue("reference", required(reference, "jobReference")),
                        (rs, rowNum) -> rs.getLong("id"));
        if (rows.isEmpty()) {
            throw new PaymentGatewayException("Validation job was not found");
        }
        return rows.get(0);
    }

    private JobContext jobContext(long id) {
        List<JobContext> rows =
                jdbc.query(
                        "SELECT j.merchant_id,j.job_reference,j.source_payload,p.pack_code,v.version_number,v.rules_json "
                                + "FROM validation_jobs j JOIN validation_rule_pack_versions v ON v.id=j.rule_pack_version_id "
                                + "JOIN validation_rule_packs p ON p.id=v.rule_pack_id WHERE j.id=:id",
                        new MapSqlParameterSource("id", id),
                        (rs, rowNum) ->
                                new JobContext(
                                        rs.getLong("merchant_id"),
                                        rs.getString("job_reference"),
                                        rs.getString("source_payload"),
                                        rs.getString("pack_code"),
                                        rs.getString("version_number"),
                                        rs.getString("rules_json")));
        if (rows.isEmpty()) {
            throw new PaymentGatewayException("Validation job context was not found");
        }
        return rows.get(0);
    }

    private Map<String, Object> job(long merchantId, String reference, boolean unused) {
        return job(merchantId, reference);
    }

    private String environment(String value) {
        String normalized = normalized(value);
        if (!"SANDBOX".equals(normalized) && !"PRODUCTION".equals(normalized)) {
            throw new PaymentGatewayException("environment must be SANDBOX or PRODUCTION");
        }
        return normalized;
    }

    private String normalized(String value) {
        return required(value, "value").toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
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

    private String reference(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
    }

    private String sha256(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String safe(String value) {
        if (value == null) {
            return "Validation failed";
        }
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }

    private record JobContext(
            long merchantId,
            String jobReference,
            String sourcePayload,
            String packCode,
            String versionNumber,
            String rulesJson) {}

    private record Finding(
            String field,
            String code,
            String severity,
            String category,
            String message,
            String remediation) {}
}
