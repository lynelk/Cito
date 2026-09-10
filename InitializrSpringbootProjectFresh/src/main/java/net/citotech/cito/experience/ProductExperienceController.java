package net.citotech.cito.experience;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.citotech.cito.experience.ExperienceAccessContext.Access;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(path = "/api/v2", produces = "application/json")
public class ProductExperienceController {
    private static final Set<String> CASE_SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> INCIDENT_SEVERITIES = Set.of("MINOR", "MAJOR", "CRITICAL");
    private static final int MAX_RESULTS = 100;

    private final NamedParameterJdbcTemplate jdbc;
    private final ExperienceAccessContext accessContext;
    private final MerchantActivationLifecycleService lifecycleService;

    public ProductExperienceController(
            NamedParameterJdbcTemplate jdbc,
            ExperienceAccessContext accessContext,
            MerchantActivationLifecycleService lifecycleService) {
        this.jdbc = jdbc;
        this.accessContext = accessContext;
        this.lifecycleService = lifecycleService;
    }

    @GetMapping("/merchants/{merchantId}/overview")
    public Map<String, Object> merchantOverview(
            @PathVariable long merchantId,
            HttpServletRequest request,
            Authentication authentication) {
        Access access = accessContext.require(request, authentication);
        accessContext.requireMerchantScope(access, merchantId);
        lifecycleService.ensure(merchantId);

        Map<String, Object> merchant = requiredMerchant(merchantId);
        MapSqlParameterSource params = new MapSqlParameterSource("merchantId", merchantId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("merchant", merchant);
        response.put("lifecycle", lifecycleSummary(merchantId));
        response.put(
                "transactionSummary",
                jdbc.queryForMap(
                        """
                        SELECT COUNT(*) transaction_count,
                               COALESCE(SUM(CASE WHEN tx_type='PAYIN' AND status='SUCCESSFUL' THEN original_amount ELSE 0 END),0) successful_collections,
                               COALESCE(SUM(CASE WHEN tx_type='PAYOUT' AND status='SUCCESSFUL' THEN original_amount ELSE 0 END),0) successful_payouts,
                               COALESCE(SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END),0) failed_count,
                               MAX(updated_on) data_as_of
                        FROM merchant_production_transactions WHERE merchant_id=:merchantId
                        """,
                        params));
        response.put(
                "entitlements",
                jdbc.queryForList(
                        """
                        SELECT e.service_code, c.service_name, e.environment, e.status, e.plan_code,
                               e.starts_at, e.ends_at, e.updated_at
                        FROM cito_organizations o
                        JOIN cito_service_entitlements e ON e.organization_id=o.id
                        JOIN cito_service_catalog c ON c.service_code=e.service_code
                        WHERE o.merchant_id=:merchantId
                        ORDER BY c.service_name, e.environment
                        """,
                        params));
        response.put(
                "openSupportCases",
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM support_cases WHERE merchant_id=:merchantId AND status NOT IN ('RESOLVED','CLOSED')",
                        params,
                        Integer.class));
        response.put("environment", requestEnvironment(request));
        response.put("generatedAt", Instant.now());
        response.put("dataSource", "live");
        return response;
    }

    @GetMapping("/merchants/{merchantId}/lifecycle")
    public Map<String, Object> merchantLifecycle(
            @PathVariable long merchantId,
            HttpServletRequest request,
            Authentication authentication) {
        Access access = accessContext.require(request, authentication);
        accessContext.requireMerchantScope(access, merchantId);
        lifecycleService.ensure(merchantId);
        Map<String, Object> response = new LinkedHashMap<>(lifecycleSummary(merchantId));
        response.put(
                "steps",
                jdbc.queryForList(
                        """
                        SELECT s.step_code, s.step_name, s.status, s.responsible_party,
                               s.required_for_activation, s.guidance, s.blocker, s.completed_by,
                               s.completed_at, s.sort_order, s.updated_at
                        FROM merchant_activation_steps s
                        JOIN merchant_activation_lifecycles l ON l.id=s.lifecycle_id
                        WHERE l.merchant_id=:merchantId ORDER BY s.sort_order
                        """,
                        new MapSqlParameterSource("merchantId", merchantId)));
        return response;
    }

    @Transactional
    @PatchMapping("/merchants/{merchantId}/lifecycle/steps/{stepCode}")
    public Map<String, Object> updateMerchantLifecycleStep(
            @PathVariable long merchantId,
            @PathVariable String stepCode,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request,
            Authentication authentication) {
        Access access = accessContext.require(request, authentication);
        accessContext.requireMerchantScope(access, merchantId);
        String status = requireText(body.get("status"), "status", 2, 40);
        String blocker = optionalText(body.get("blocker"), 1000);
        lifecycleService.updateStep(
                merchantId, stepCode, status, access.actor(), blocker, access.admin());
        audit(
                access,
                "MERCHANT_LIFECYCLE_STEP_UPDATED",
                merchantId + ":" + stepCode,
                "Status " + status.toUpperCase(Locale.ROOT));
        notifyMerchant(
                merchantId,
                "ACTIVATION_UPDATED",
                blocker == null ? "INFO" : "HIGH",
                "Activation step updated",
                stepCode.replace('_', ' ')
                        + " is now "
                        + status.replace('_', ' ').toLowerCase(Locale.ROOT)
                        + ".",
                "/bo/partner/home");
        return merchantLifecycle(merchantId, request, authentication);
    }

    @GetMapping("/transactions/{reference}")
    public Map<String, Object> transaction(
            @PathVariable String reference,
            HttpServletRequest request,
            Authentication authentication) {
        Access access = accessContext.require(request, authentication);
        Map<String, Object> row = requiredTransaction(reference, access);
        row.computeIfPresent("payer_number", (key, value) -> mask(String.valueOf(value)));
        row.put("finality", finality(String.valueOf(row.get("status"))));
        row.put("environment", requestEnvironment(request));
        return row;
    }

    @GetMapping("/transactions/{reference}/timeline")
    public Map<String, Object> transactionTimeline(
            @PathVariable String reference,
            HttpServletRequest request,
            Authentication authentication) {
        Access access = accessContext.require(request, authentication);
        Map<String, Object> transaction = requiredTransaction(reference, access);
        long transactionId = ((Number) transaction.get("id")).longValue();
        List<Map<String, Object>> events = new ArrayList<>();
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("event", "REQUEST_ACCEPTED");
        created.put("status", "PENDING");
        created.put("occurredAt", transaction.get("created_on"));
        created.put("source", "CITO");
        events.add(created);
        events.addAll(
                jdbc.queryForList(
                        """
                        SELECT event_source event, next_status status, previous_status,
                               transition_result result, provider_reference, reason,
                               created_at occurredAt
                        FROM payment_state_transitions
                        WHERE transaction_log_id=:transactionId ORDER BY created_at, id
                        """,
                        new MapSqlParameterSource("transactionId", transactionId)));
        List<Map<String, Object>> reconciliation =
                jdbc.queryForList(
                        """
                        SELECT match_status status, match_reason reason, exception_category,
                               settlement_batch, provider_reference, updated_at occurredAt
                        FROM reconciliation_records
                        WHERE transaction_id=:uniqueReference OR merchant_reference=:merchantReference
                        ORDER BY updated_at, id
                        """,
                        new MapSqlParameterSource()
                                .addValue("uniqueReference", transaction.get("tx_unique_id"))
                                .addValue("merchantReference", transaction.get("tx_merchant_ref")));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reference", transaction.get("tx_unique_id"));
        response.put("providerReference", transaction.get("tx_gateway_ref"));
        response.put("merchantReference", transaction.get("tx_merchant_ref"));
        response.put("finality", finality(String.valueOf(transaction.get("status"))));
        response.put("events", events);
        response.put("reconciliation", reconciliation);
        response.put("settlementState", reconciliation.isEmpty() ? "NOT_RECONCILED" : "RECORDED");
        response.put("generatedAt", Instant.now());
        return response;
    }

    @GetMapping("/transactions/{reference}/support-context")
    public Map<String, Object> transactionSupportContext(
            @PathVariable String reference,
            HttpServletRequest request,
            Authentication authentication) {
        Access access = accessContext.require(request, authentication);
        Map<String, Object> transaction = requiredTransaction(reference, access);
        long merchantId = ((Number) transaction.get("merchant_id")).longValue();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("transaction", transaction(reference, request, authentication));
        response.put("lifecycle", lifecycleSummary(merchantId));
        response.put(
                "relatedCases",
                jdbc.queryForList(
                        """
                        SELECT case_reference, subject, category, severity, status, assigned_team,
                               first_response_due_at, resolution_due_at, updated_at
                        FROM support_cases
                        WHERE merchant_id=:merchantId AND transaction_reference IN (:reference,:uniqueReference,:merchantReference)
                        ORDER BY updated_at DESC
                        """,
                        new MapSqlParameterSource()
                                .addValue("merchantId", merchantId)
                                .addValue("reference", reference)
                                .addValue("uniqueReference", transaction.get("tx_unique_id"))
                                .addValue(
                                        "merchantReference", transaction.get("tx_merchant_ref"))));
        response.put("safeToRetry", false);
        response.put(
                "retryGuidance",
                "Do not retry until transaction finality and idempotency evidence have been reviewed.");
        return response;
    }

    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "25") int limit,
            HttpServletRequest request,
            Authentication authentication) {
        Access access = accessContext.require(request, authentication);
        String query = requireText(q, "q", 2, 120);
        int safeLimit = Math.max(1, Math.min(limit, MAX_RESULTS));
        MapSqlParameterSource params =
                new MapSqlParameterSource("query", "%" + escapeLike(query) + "%")
                        .addValue("limit", safeLimit);
        String txScope = "";
        String caseScope = "";
        if (!access.admin()) {
            params.addValue("merchantId", access.merchantId());
            txScope = " AND merchant_id=:merchantId";
            caseScope = " AND merchant_id=:merchantId";
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", query);
        response.put("scope", access.admin() ? "PLATFORM" : "MERCHANT");
        response.put(
                "transactions",
                jdbc.queryForList(
                        """
                        SELECT tx_unique_id reference, tx_merchant_ref merchant_reference,
                               tx_gateway_ref provider_reference, status, tx_type, original_amount,
                               currency, merchant_id, updated_on
                        FROM merchant_production_transactions
                        WHERE (tx_unique_id LIKE :query ESCAPE '\\\\'
                           OR tx_merchant_ref LIKE :query ESCAPE '\\\\'
                           OR tx_gateway_ref LIKE :query ESCAPE '\\\\')
                        """
                                + txScope
                                + " ORDER BY updated_on DESC LIMIT :limit",
                        params));
        response.put(
                "supportCases",
                jdbc.queryForList(
                        """
                        SELECT case_reference reference, merchant_id, subject, category, severity,
                               status, transaction_reference, updated_at
                        FROM support_cases
                        WHERE (case_reference LIKE :query ESCAPE '\\\\'
                           OR subject LIKE :query ESCAPE '\\\\'
                           OR transaction_reference LIKE :query ESCAPE '\\\\')
                        """
                                + caseScope
                                + " ORDER BY updated_at DESC LIMIT :limit",
                        params));
        response.put(
                "merchants",
                access.admin()
                        ? jdbc.queryForList(
                                """
                                SELECT id, account_number reference, name, short_name, status, account_type, updated_on
                                FROM merchants
                                WHERE account_number LIKE :query ESCAPE '\\\\'
                                   OR name LIKE :query ESCAPE '\\\\'
                                   OR short_name LIKE :query ESCAPE '\\\\'
                                ORDER BY updated_on DESC LIMIT :limit
                                """,
                                params)
                        : List.of());
        response.put("generatedAt", Instant.now());
        return response;
    }

    @GetMapping("/support/cases")
    public Map<String, Object> supportCases(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request,
            Authentication authentication) {
        Access access = accessContext.require(request, authentication);
        MapSqlParameterSource params =
                new MapSqlParameterSource("limit", Math.max(1, Math.min(limit, MAX_RESULTS)));
        StringBuilder sql =
                new StringBuilder(
                        "SELECT case_reference, merchant_id, subject, category, severity, status, channel, "
                                + "transaction_reference, provider_reference, assigned_team, assigned_to, opened_by, "
                                + "first_response_due_at, resolution_due_at, resolved_at, created_at, updated_at "
                                + "FROM support_cases WHERE 1=1");
        if (!access.admin()) {
            sql.append(" AND merchant_id=:merchantId");
            params.addValue("merchantId", access.merchantId());
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status=:status");
            params.addValue("status", status.trim().toUpperCase(Locale.ROOT));
        }
        sql.append(" ORDER BY updated_at DESC LIMIT :limit");
        return Map.of(
                "cases", jdbc.queryForList(sql.toString(), params), "generatedAt", Instant.now());
    }

    @Transactional
    @PostMapping("/support/cases")
    public Map<String, Object> createSupportCase(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request,
            Authentication authentication) {
        Access access = accessContext.require(request, authentication);
        Long merchantId =
                access.admin() ? optionalLong(body.get("merchantId")) : access.merchantId();
        if (merchantId != null) {
            requiredMerchant(merchantId);
        }
        String reference = reference("CASE");
        String severity = enumValue(body, "severity", "MEDIUM", CASE_SEVERITIES);
        Instant now = Instant.now();
        Instant firstResponseDue =
                now.plus(
                        severity.equals("CRITICAL") ? 1 : severity.equals("HIGH") ? 4 : 24,
                        ChronoUnit.HOURS);
        Instant resolutionDue =
                now.plus(
                        severity.equals("CRITICAL") ? 4 : severity.equals("HIGH") ? 24 : 72,
                        ChronoUnit.HOURS);
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("reference", reference)
                        .addValue("merchantId", merchantId)
                        .addValue("subject", requireText(body.get("subject"), "subject", 4, 240))
                        .addValue(
                                "category",
                                requireText(body.get("category"), "category", 2, 80)
                                        .toUpperCase(Locale.ROOT))
                        .addValue("severity", severity)
                        .addValue(
                                "transactionReference",
                                optionalText(body.get("transactionReference"), 255))
                        .addValue(
                                "providerReference",
                                optionalText(body.get("providerReference"), 255))
                        .addValue("actor", access.actor())
                        .addValue(
                                "description",
                                requireText(body.get("description"), "description", 10, 5000))
                        .addValue("firstResponseDue", firstResponseDue)
                        .addValue("resolutionDue", resolutionDue);
        jdbc.update(
                """
                INSERT INTO support_cases
                    (case_reference, merchant_id, subject, category, severity, transaction_reference,
                     provider_reference, opened_by, description, first_response_due_at, resolution_due_at)
                VALUES (:reference,:merchantId,:subject,:category,:severity,:transactionReference,
                        :providerReference,:actor,:description,:firstResponseDue,:resolutionDue)
                """,
                params);
        Long caseId =
                jdbc.queryForObject(
                        "SELECT id FROM support_cases WHERE case_reference=:reference",
                        params,
                        Long.class);
        params.addValue("caseId", caseId);
        jdbc.update(
                """
                INSERT INTO support_case_events
                    (support_case_id,event_type,actor_type,actor_reference,message)
                VALUES (:caseId,'CREATED',:actorType,:actor,:description)
                """,
                params.addValue("actorType", access.admin() ? "ADMIN" : "MERCHANT"));
        notifyMerchant(
                merchantId,
                "SUPPORT_CASE_CREATED",
                severity,
                "Support case " + reference + " created",
                "Your support case has been recorded and assigned its response targets.",
                "/bo/partner/help");
        audit(access, "SUPPORT_CASE_CREATED", reference, "Support case created through portal");
        return Map.of("caseReference", reference, "status", "OPEN", "createdAt", now);
    }

    @GetMapping("/support/cases/{reference}")
    public Map<String, Object> supportCase(
            @PathVariable String reference,
            HttpServletRequest request,
            Authentication authentication) {
        Access access = accessContext.require(request, authentication);
        Map<String, Object> supportCase = requiredSupportCase(reference, access);
        String visibility = access.admin() ? "" : " AND visibility='CUSTOMER'";
        List<Map<String, Object>> events =
                jdbc.queryForList(
                        """
                        SELECT event_type,actor_type,actor_reference,visibility,message,created_at
                        FROM support_case_events WHERE support_case_id=:caseId
                        """
                                + visibility
                                + " ORDER BY created_at,id",
                        new MapSqlParameterSource("caseId", supportCase.get("id")));
        supportCase.remove("id");
        return Map.of("case", supportCase, "events", events);
    }

    @Transactional
    @PostMapping("/support/cases/{reference}/events")
    public Map<String, Object> addSupportCaseEvent(
            @PathVariable String reference,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request,
            Authentication authentication) {
        Access access = accessContext.require(request, authentication);
        Map<String, Object> supportCase = requiredSupportCase(reference, access);
        String visibility =
                access.admin()
                        ? enumValue(body, "visibility", "CUSTOMER", Set.of("CUSTOMER", "INTERNAL"))
                        : "CUSTOMER";
        String eventType =
                enumValue(
                        body,
                        "eventType",
                        "COMMENT",
                        Set.of(
                                "COMMENT",
                                "CUSTOMER_REPLY",
                                "AGENT_REPLY",
                                "ESCALATED",
                                "EVIDENCE_ADDED"));
        String message = requireText(body.get("message"), "message", 2, 5000);
        jdbc.update(
                """
                INSERT INTO support_case_events
                    (support_case_id,event_type,actor_type,actor_reference,visibility,message)
                VALUES (:caseId,:eventType,:actorType,:actor,:visibility,:message)
                """,
                new MapSqlParameterSource("caseId", supportCase.get("id"))
                        .addValue("eventType", eventType)
                        .addValue("actorType", access.admin() ? "ADMIN" : "MERCHANT")
                        .addValue("actor", access.actor())
                        .addValue("visibility", visibility)
                        .addValue("message", message));
        jdbc.update(
                "UPDATE support_cases SET updated_at=CURRENT_TIMESTAMP WHERE id=:caseId",
                new MapSqlParameterSource("caseId", supportCase.get("id")));
        Long merchantId = optionalLong(supportCase.get("merchant_id"));
        if (access.admin() && "CUSTOMER".equals(visibility)) {
            notifyMerchant(
                    merchantId,
                    "SUPPORT_CASE_UPDATED",
                    "INFO",
                    "Support case " + reference + " updated",
                    "A support agent added a response to your case.",
                    "/bo/partner/help");
        }
        audit(access, "SUPPORT_CASE_EVENT_ADDED", reference, eventType);
        return Map.of("caseReference", reference, "eventType", eventType, "recorded", true);
    }

    @Transactional
    @PatchMapping("/support/cases/{reference}")
    public Map<String, Object> updateSupportCase(
            @PathVariable String reference,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request,
            Authentication authentication) {
        Access access = accessContext.require(request, authentication);
        accessContext.requireAdmin(access);
        Map<String, Object> supportCase = requiredSupportCase(reference, access);
        String status =
                enumValue(
                        body,
                        "status",
                        String.valueOf(supportCase.get("status")),
                        Set.of(
                                "OPEN",
                                "IN_PROGRESS",
                                "WAITING_FOR_CUSTOMER",
                                "ESCALATED",
                                "RESOLVED",
                                "CLOSED"));
        String assignedTeam = optionalText(body.get("assignedTeam"), 80);
        String assignedTo = optionalText(body.get("assignedTo"), 190);
        jdbc.update(
                """
                UPDATE support_cases
                SET status=:status,assigned_team=COALESCE(:assignedTeam,assigned_team),
                    assigned_to=COALESCE(:assignedTo,assigned_to),
                    resolved_at=CASE WHEN :status IN ('RESOLVED','CLOSED') THEN COALESCE(resolved_at,CURRENT_TIMESTAMP) ELSE resolved_at END
                WHERE id=:caseId
                """,
                new MapSqlParameterSource("status", status)
                        .addValue("assignedTeam", assignedTeam)
                        .addValue("assignedTo", assignedTo)
                        .addValue("caseId", supportCase.get("id")));
        notifyMerchant(
                optionalLong(supportCase.get("merchant_id")),
                "SUPPORT_CASE_STATUS",
                "INFO",
                "Support case "
                        + reference
                        + " is "
                        + status.replace('_', ' ').toLowerCase(Locale.ROOT),
                "Open the support centre for the latest case details.",
                "/bo/partner/help");
        audit(access, "SUPPORT_CASE_UPDATED", reference, "Status " + status);
        return Map.of("caseReference", reference, "status", status);
    }

    @GetMapping("/notifications")
    public Map<String, Object> notifications(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request,
            Authentication authentication) {
        Access access = accessContext.require(request, authentication);
        MapSqlParameterSource params =
                new MapSqlParameterSource("recipientType", access.admin() ? "ADMIN" : "MERCHANT")
                        .addValue(
                                "recipientReference",
                                access.admin()
                                        ? access.actor()
                                        : String.valueOf(access.merchantId()))
                        .addValue("limit", Math.max(1, Math.min(limit, MAX_RESULTS)));
        String unread = unreadOnly ? " AND read_at IS NULL" : "";
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        """
                        SELECT notification_reference, notification_type, severity, title, message,
                               action_url, read_at, expires_at, created_at
                        FROM user_notifications
                        WHERE recipient_type=:recipientType AND recipient_reference=:recipientReference
                          AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)
                        """
                                + unread
                                + " ORDER BY created_at DESC LIMIT :limit",
                        params);
        Integer unreadCount =
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*) FROM user_notifications
                        WHERE recipient_type=:recipientType AND recipient_reference=:recipientReference
                          AND read_at IS NULL AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)
                        """,
                        params,
                        Integer.class);
        return Map.of(
                "notifications", rows, "unreadCount", unreadCount, "generatedAt", Instant.now());
    }

    @Transactional
    @PatchMapping("/notifications/{reference}/read")
    public Map<String, Object> readNotification(
            @PathVariable String reference,
            HttpServletRequest request,
            Authentication authentication) {
        Access access = accessContext.require(request, authentication);
        int updated =
                jdbc.update(
                        """
                        UPDATE user_notifications SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP)
                        WHERE notification_reference=:reference AND recipient_type=:recipientType
                          AND recipient_reference=:recipientReference
                        """,
                        new MapSqlParameterSource("reference", reference)
                                .addValue("recipientType", access.admin() ? "ADMIN" : "MERCHANT")
                                .addValue(
                                        "recipientReference",
                                        access.admin()
                                                ? access.actor()
                                                : String.valueOf(access.merchantId())));
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
        }
        return Map.of("notificationReference", reference, "read", true);
    }

    @GetMapping("/provider-incidents")
    public Map<String, Object> providerIncidents(
            @RequestParam(defaultValue = "false") boolean activeOnly,
            HttpServletRequest request,
            Authentication authentication) {
        Access access = accessContext.require(request, authentication);
        accessContext.requireAdmin(access);
        String active = activeOnly ? " WHERE status NOT IN ('RESOLVED','CLOSED')" : "";
        return Map.of(
                "incidents",
                jdbc.queryForList(
                        "SELECT * FROM provider_incidents"
                                + active
                                + " ORDER BY started_at DESC LIMIT 100",
                        new MapSqlParameterSource()),
                "generatedAt",
                Instant.now());
    }

    @Transactional
    @PostMapping("/provider-incidents")
    public Map<String, Object> createProviderIncident(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request,
            Authentication authentication) {
        Access access = accessContext.require(request, authentication);
        accessContext.requireAdmin(access);
        String reference = reference("INC");
        MapSqlParameterSource params =
                new MapSqlParameterSource("reference", reference)
                        .addValue(
                                "providerCode",
                                requireText(body.get("providerCode"), "providerCode", 2, 80)
                                        .toUpperCase(Locale.ROOT))
                        .addValue("countryCode", optionalText(body.get("countryCode"), 3))
                        .addValue("channelCode", optionalText(body.get("channelCode"), 80))
                        .addValue(
                                "environment",
                                enumValue(
                                        body,
                                        "environment",
                                        "PRODUCTION",
                                        Set.of("SANDBOX", "PRODUCTION")))
                        .addValue(
                                "severity",
                                enumValue(body, "severity", "MINOR", INCIDENT_SEVERITIES))
                        .addValue(
                                "title",
                                requireText(body.get("publicTitle"), "publicTitle", 4, 240))
                        .addValue(
                                "message",
                                requireText(body.get("publicMessage"), "publicMessage", 10, 1000))
                        .addValue("internalNotes", optionalText(body.get("internalNotes"), 5000))
                        .addValue("actor", access.actor());
        jdbc.update(
                """
                INSERT INTO provider_incidents
                    (incident_reference,provider_code,country_code,channel_code,environment,severity,
                     public_title,public_message,internal_notes,started_at,created_by)
                VALUES (:reference,:providerCode,:countryCode,:channelCode,:environment,:severity,
                        :title,:message,:internalNotes,CURRENT_TIMESTAMP,:actor)
                """,
                params);
        audit(access, "PROVIDER_INCIDENT_CREATED", reference, "Provider incident opened");
        return Map.of("incidentReference", reference, "status", "INVESTIGATING");
    }

    @Transactional
    @PatchMapping("/provider-incidents/{reference}")
    public Map<String, Object> updateProviderIncident(
            @PathVariable String reference,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request,
            Authentication authentication) {
        Access access = accessContext.require(request, authentication);
        accessContext.requireAdmin(access);
        String status =
                enumValue(
                        body,
                        "status",
                        "MONITORING",
                        Set.of("INVESTIGATING", "IDENTIFIED", "MONITORING", "RESOLVED", "CLOSED"));
        String publicMessage = optionalText(body.get("publicMessage"), 1000);
        String internalNotes = optionalText(body.get("internalNotes"), 5000);
        int updated =
                jdbc.update(
                        """
                        UPDATE provider_incidents
                        SET status=:status,public_message=COALESCE(:publicMessage,public_message),
                            internal_notes=COALESCE(:internalNotes,internal_notes),
                            resolved_at=CASE WHEN :status IN ('RESOLVED','CLOSED') THEN COALESCE(resolved_at,CURRENT_TIMESTAMP) ELSE resolved_at END
                        WHERE incident_reference=:reference
                        """,
                        new MapSqlParameterSource("status", status)
                                .addValue("publicMessage", publicMessage)
                                .addValue("internalNotes", internalNotes)
                                .addValue("reference", reference));
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider incident not found");
        }
        audit(access, "PROVIDER_INCIDENT_UPDATED", reference, "Status " + status);
        return Map.of("incidentReference", reference, "status", status);
    }

    private Map<String, Object> requiredMerchant(long merchantId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        """
                        SELECT id, name, short_name, account_number, account_type, status, created_on, updated_on
                        FROM merchants WHERE id=:merchantId
                        """,
                        new MapSqlParameterSource("merchantId", merchantId));
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Merchant not found");
        }
        return new LinkedHashMap<>(rows.get(0));
    }

    private Map<String, Object> lifecycleSummary(long merchantId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        """
                        SELECT lifecycle_reference, merchant_id, status, current_step_code,
                               next_action, blocked_reason, assigned_owner, due_at, activated_at,
                               created_at, updated_at
                        FROM merchant_activation_lifecycles WHERE merchant_id=:merchantId
                        """,
                        new MapSqlParameterSource("merchantId", merchantId));
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Merchant lifecycle not found");
        }
        return rows.get(0);
    }

    private Map<String, Object> requiredTransaction(String reference, Access access) {
        MapSqlParameterSource params = new MapSqlParameterSource("reference", reference);
        String scope = "";
        if (!access.admin()) {
            scope = " AND merchant_id=:merchantId";
            params.addValue("merchantId", access.merchantId());
        }
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        """
                        SELECT id, merchant_id, gateway_id, original_amount, charges, currency, status,
                               tx_description, tx_merchant_description, tx_unique_id, tx_gateway_ref,
                               tx_merchant_ref, payer_number, tx_type, account_type, resolved_by,
                               created_on, updated_on
                        FROM merchant_production_transactions
                        WHERE (tx_unique_id=:reference OR tx_gateway_ref=:reference OR tx_merchant_ref=:reference)
                        """
                                + scope
                                + " ORDER BY id DESC LIMIT 1",
                        params);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found");
        }
        return new LinkedHashMap<>(rows.get(0));
    }

    private Map<String, Object> requiredSupportCase(String reference, Access access) {
        MapSqlParameterSource params = new MapSqlParameterSource("reference", reference);
        String scope = "";
        if (!access.admin()) {
            params.addValue("merchantId", access.merchantId());
            scope = " AND merchant_id=:merchantId";
        }
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT * FROM support_cases WHERE case_reference=:reference" + scope,
                        params);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Support case not found");
        }
        return new LinkedHashMap<>(rows.get(0));
    }

    private void notifyMerchant(
            Long merchantId,
            String type,
            String severity,
            String title,
            String message,
            String actionUrl) {
        if (merchantId == null) {
            return;
        }
        jdbc.update(
                """
                INSERT INTO user_notifications
                    (notification_reference,recipient_type,recipient_reference,merchant_id,
                     notification_type,severity,title,message,action_url)
                VALUES (:reference,'MERCHANT',:recipientReference,:merchantId,:type,:severity,:title,:message,:actionUrl)
                """,
                new MapSqlParameterSource("reference", reference("NTF"))
                        .addValue("recipientReference", String.valueOf(merchantId))
                        .addValue("merchantId", merchantId)
                        .addValue("type", type)
                        .addValue("severity", severity)
                        .addValue("title", title)
                        .addValue("message", message)
                        .addValue("actionUrl", actionUrl));
    }

    private void audit(Access access, String action, String reference, String summary) {
        if (!access.admin()) {
            jdbc.update(
                    """
                    INSERT INTO merchants_audit_trail (merchant_id,user_name,user_id,action)
                    VALUES (:merchantId,:actor,:actor,:summary)
                    """,
                    new MapSqlParameterSource("merchantId", access.merchantId())
                            .addValue("actor", access.actor())
                            .addValue("summary", action + ": " + summary + " [" + reference + "]"));
            return;
        }
        jdbc.update(
                """
                INSERT INTO admin_audit_events (actor,permission_code,action_name,resource_reference,request_summary)
                VALUES (:actor,'PRODUCT_EXPERIENCE',:action,:reference,:summary)
                """,
                new MapSqlParameterSource("actor", access.actor())
                        .addValue("action", action)
                        .addValue("reference", reference)
                        .addValue("summary", summary));
    }

    private String finality(String status) {
        return switch (status) {
            case "SUCCESSFUL", "FAILED" -> "FINAL";
            case "UNDETERMINED" -> "REVIEW_REQUIRED";
            default -> "NON_FINAL";
        };
    }

    private String requestEnvironment(HttpServletRequest request) {
        String environment = request.getHeader("X-Cito-Environment");
        if (environment == null || environment.isBlank()) {
            return "UNSPECIFIED";
        }
        String normalized = environment.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("SANDBOX", "PRODUCTION").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported environment");
        }
        return normalized;
    }

    private String reference(String prefix) {
        return prefix
                + "-"
                + UUID.randomUUID()
                        .toString()
                        .replace("-", "")
                        .substring(0, 20)
                        .toUpperCase(Locale.ROOT);
    }

    private String enumValue(
            Map<String, Object> body, String field, String defaultValue, Set<String> allowed) {
        Object raw = body.get(field);
        String value =
                raw == null ? defaultValue : String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported " + field);
        }
        return value;
    }

    private String requireText(Object raw, String field, int min, int max) {
        String value = raw == null ? "" : String.valueOf(raw).trim();
        if (value.length() < min || value.length() > max) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    field + " must be between " + min + " and " + max + " characters");
        }
        return value;
    }

    private String optionalText(Object raw, int max) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        if (value.length() > max) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Field exceeds " + max + " characters");
        }
        return value;
    }

    private Long optionalLong(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "merchantId must be numeric");
        }
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String compact = value.trim();
        return compact.length() <= 4
                ? "****"
                : "*".repeat(Math.min(8, compact.length() - 4))
                        + compact.substring(compact.length() - 4);
    }

    static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
