package net.citotech.cito.communication;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.citotech.cito.Model.MerchantUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Campaign, delivery-report and usage surfaces for the merchant SMS workspace. */
@RestController
@RequestMapping("/api/v2/merchant/communication/sms")
public class MerchantSmsOperationsController {

    private static final int MAX_CAMPAIGN_RECIPIENTS = 5000;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final MerchantCommunicationService communicationService;

    public MerchantSmsOperationsController(
            NamedParameterJdbcTemplate jdbcTemplate,
            MerchantCommunicationService communicationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.communicationService = communicationService;
    }

    @GetMapping("/campaigns")
    public ResponseEntity<?> campaigns(
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            HttpServletRequest request) {
        MerchantUser user = merchantUser(request);
        if (user == null) return unauthorized();
        int safeLimit = Math.max(1, Math.min(limit, 250));
        String sql =
                "SELECT c.id,c.name,c.status,c.total_recipients totalRecipients,"
                        + " c.processed_recipients processedRecipients,c.scheduled_at scheduledAt,c.created_at createdAt,c.updated_at updatedAt,"
                        + " SUM(CASE WHEN COALESCE(m.status,i.status) IN ('SENT','DELIVERED') THEN 1 ELSE 0 END) successful,"
                        + " SUM(CASE WHEN COALESCE(m.status,i.status) IN ('FAILED','REJECTED','EXPIRED') THEN 1 ELSE 0 END) failed,"
                        + " SUM(CASE WHEN i.status='SUPPRESSED' THEN 1 ELSE 0 END) suppressed"
                        + " FROM communication_campaigns c"
                        + " LEFT JOIN communication_campaign_items i ON i.campaign_id=c.id"
                        + " LEFT JOIN communication_messages m ON m.public_id=i.message_reference AND m.merchant_id=c.merchant_id"
                        + " WHERE c.merchant_id=:merchant AND c.channel='SMS'"
                        + " GROUP BY c.id,c.name,c.status,c.total_recipients,c.processed_recipients,c.scheduled_at,c.created_at,c.updated_at"
                        + " ORDER BY c.created_at DESC LIMIT :limit";
        return ResponseEntity.ok(
                jdbcTemplate.queryForList(
                        sql,
                        new MapSqlParameterSource()
                                .addValue("merchant", merchantId(user))
                                .addValue("limit", safeLimit)));
    }

    @PostMapping(path = "/campaigns", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> sendCampaign(
            @RequestBody CampaignRequest input, HttpServletRequest request) {
        MerchantUser user = merchantUser(request);
        if (user == null) return unauthorized();
        if (input == null || blank(input.name())) return bad("Campaign name is required.");
        if (blank(input.content())) return bad("Campaign message is required.");
        if (!hasAudience(input))
            return bad("Add recipients, contacts or at least one contact group.");

        long merchantId = merchantId(user);
        try {
            LinkedHashMap<String, CampaignRecipient> unique = expandAudience(merchantId, input);
            if (unique.isEmpty()) return bad("No valid campaign recipients were found.");
            if (unique.size() > MAX_CAMPAIGN_RECIPIENTS) {
                return bad(
                        "A campaign can contain at most 5,000 unique recipients per submission.");
            }

            Timestamp scheduled = scheduleTimestamp(input.scheduledAt());
            String status = scheduled == null ? "QUEUED" : "SCHEDULED";
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(
                    "INSERT INTO communication_campaigns"
                            + " (merchant_id,name,channel,total_recipients,processed_recipients,status,scheduled_at,created_by)"
                            + " VALUES (:merchant,:name,'SMS',:total,0,:status,:scheduled,:createdBy)",
                    new MapSqlParameterSource()
                            .addValue("merchant", merchantId)
                            .addValue("name", input.name().trim())
                            .addValue("total", unique.size())
                            .addValue("status", status)
                            .addValue("scheduled", scheduled)
                            .addValue("createdBy", userLabel(user)),
                    keyHolder,
                    new String[] {"id"});
            Number key = keyHolder.getKey();
            if (key == null) throw new IllegalStateException("Campaign could not be created.");
            long campaignId = key.longValue();

            int accepted = 0;
            int suppressed = 0;
            int index = 0;
            List<Map<String, Object>> messages = new ArrayList<>();
            String purpose =
                    blank(input.purpose())
                            ? "MARKETING"
                            : input.purpose().trim().toUpperCase(Locale.ROOT);
            for (CampaignRecipient recipient : unique.values()) {
                String rendered = personalize(input.content(), recipient.variables());
                if ("MARKETING".equals(purpose) && isSuppressed(merchantId, recipient.phone())) {
                    insertCampaignItem(
                            campaignId,
                            recipient.phone(),
                            null,
                            rendered,
                            "SUPPRESSED",
                            "Marketing opt-out");
                    suppressed++;
                    index++;
                    continue;
                }
                String reference = "CMP-" + campaignId + ":" + index;
                Map<String, Object> queued =
                        communicationService.enqueueSms(
                                merchantId,
                                recipient.phone(),
                                rendered,
                                purpose,
                                reference,
                                reference,
                                input.expiresInSeconds(),
                                new MerchantCommunicationService.SmsOptions(
                                        input.senderId(),
                                        input.scheduledAt(),
                                        input.routingStrategy(),
                                        input.countryCode(),
                                        input.currencyCode(),
                                        input.requireDeliveryReceipts() == null
                                                || input.requireDeliveryReceipts(),
                                        Boolean.TRUE.equals(input.requireInbound()),
                                        input.fallbackEnabled() == null
                                                || input.fallbackEnabled()));
                String messageReference =
                        String.valueOf(queued.getOrDefault("messageReference", ""));
                insertCampaignItem(
                        campaignId,
                        recipient.phone(),
                        blank(messageReference) ? null : messageReference,
                        rendered,
                        "QUEUED",
                        reference);
                messages.add(queued);
                accepted++;
                index++;
            }
            jdbcTemplate.update(
                    "UPDATE communication_campaigns SET processed_recipients=:processed,updated_at=NOW()"
                            + " WHERE id=:id AND merchant_id=:merchant",
                    new MapSqlParameterSource()
                            .addValue("processed", accepted + suppressed)
                            .addValue("id", campaignId)
                            .addValue("merchant", merchantId));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("campaignId", campaignId);
            response.put("name", input.name().trim());
            response.put("audience", unique.size());
            response.put("accepted", accepted);
            response.put("suppressed", suppressed);
            response.put("scheduled", scheduled != null);
            response.put("messages", messages);
            return ResponseEntity.accepted().body(response);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @GetMapping("/delivery-reports")
    public ResponseEntity<?> deliveryReports(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "senderId", required = false) String senderId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", defaultValue = "200") int limit,
            HttpServletRequest request) {
        MerchantUser user = merchantUser(request);
        if (user == null) return unauthorized();
        try {
            int safeLimit = Math.max(1, Math.min(limit, 1000));
            StringBuilder sql =
                    new StringBuilder(
                            "SELECT m.public_id messageReference,m.recipient,"
                                    + " JSON_UNQUOTE(JSON_EXTRACT(m.metadata_json,'$.senderId')) senderId,"
                                    + " COALESCE(d.status,m.status) status,COALESCE(d.provider_code,m.selected_provider_code) provider,"
                                    + " d.provider_message_id providerMessageId,d.charged_amount chargedAmount,d.billed_flag billed,"
                                    + " JSON_UNQUOTE(JSON_EXTRACT(m.metadata_json,'$.segments')) segments,"
                                    + " JSON_UNQUOTE(JSON_EXTRACT(m.metadata_json,'$.encoding')) encoding,"
                                    + " m.scheduled_at scheduledAt,m.created_at createdAt,d.updated_at deliveryUpdatedAt"
                                    + " FROM communication_messages m"
                                    + " LEFT JOIN communication_message_deliveries d ON d.id=(SELECT MAX(d2.id)"
                                    + " FROM communication_message_deliveries d2"
                                    + " WHERE d2.reference_id=m.id AND d2.merchant_id=m.merchant_id AND d2.channel='SMS')"
                                    + " WHERE m.merchant_id=:merchant AND m.selected_channel='SMS'");
            MapSqlParameterSource params = new MapSqlParameterSource("merchant", merchantId(user));
            if (!blank(from)) {
                LocalDate date = LocalDate.parse(from.trim());
                sql.append(" AND m.created_at>=:fromDate");
                params.addValue("fromDate", Timestamp.valueOf(date.atStartOfDay()));
            }
            if (!blank(to)) {
                LocalDate date = LocalDate.parse(to.trim()).plusDays(1);
                sql.append(" AND m.created_at<:toDate");
                params.addValue("toDate", Timestamp.valueOf(date.atStartOfDay()));
            }
            if (!blank(senderId)) {
                sql.append(
                        " AND JSON_UNQUOTE(JSON_EXTRACT(m.metadata_json,'$.senderId'))=:senderId");
                params.addValue("senderId", senderId.trim());
            }
            if (!blank(status)) {
                sql.append(" AND COALESCE(d.status,m.status)=:status");
                params.addValue("status", status.trim().toUpperCase(Locale.ROOT));
            }
            sql.append(" ORDER BY m.created_at DESC LIMIT :limit");
            params.addValue("limit", safeLimit);
            return ResponseEntity.ok(jdbcTemplate.queryForList(sql.toString(), params));
        } catch (Exception e) {
            return bad("Invalid delivery-report filter.");
        }
    }

    @GetMapping("/billing-summary")
    public ResponseEntity<?> billingSummary(HttpServletRequest request) {
        MerchantUser user = merchantUser(request);
        if (user == null) return unauthorized();
        long merchantId = merchantId(user);
        MapSqlParameterSource params = new MapSqlParameterSource("merchant", merchantId);
        Map<String, Object> totals =
                jdbcTemplate.queryForMap(
                        "SELECT COUNT(*) deliveryAttempts,"
                                + " SUM(CASE WHEN status IN ('SENT','DELIVERED') THEN 1 ELSE 0 END) successfulDeliveries,"
                                + " SUM(CASE WHEN status IN ('FAILED','REJECTED') THEN 1 ELSE 0 END) failedDeliveries,"
                                + " COALESCE(SUM(charged_amount),0) chargedAmount,"
                                + " SUM(CASE WHEN billed_flag='Y' THEN 1 ELSE 0 END) billedAttempts"
                                + " FROM communication_message_deliveries WHERE merchant_id=:merchant AND channel='SMS'",
                        params);
        Map<String, Object> last30Days =
                jdbcTemplate.queryForMap(
                        "SELECT COUNT(*) deliveryAttempts,"
                                + " SUM(CASE WHEN status IN ('SENT','DELIVERED') THEN 1 ELSE 0 END) successfulDeliveries,"
                                + " COALESCE(SUM(charged_amount),0) chargedAmount"
                                + " FROM communication_message_deliveries WHERE merchant_id=:merchant AND channel='SMS'"
                                + " AND created_at>=DATE_SUB(NOW(),INTERVAL 30 DAY)",
                        params);
        List<Map<String, Object>> providers =
                jdbcTemplate.queryForList(
                        "SELECT COALESCE(provider_code,'UNRESOLVED') provider,COUNT(*) attempts,"
                                + " SUM(CASE WHEN status IN ('SENT','DELIVERED') THEN 1 ELSE 0 END) successful,"
                                + " COALESCE(SUM(charged_amount),0) chargedAmount"
                                + " FROM communication_message_deliveries WHERE merchant_id=:merchant AND channel='SMS'"
                                + " GROUP BY provider_code ORDER BY attempts DESC",
                        params);
        return ResponseEntity.ok(
                Map.of("allTime", totals, "last30Days", last30Days, "providers", providers));
    }

    @PostMapping(path = "/sender-identities/request", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> requestSender(
            @RequestBody SenderEvidenceRequest input, HttpServletRequest request) {
        MerchantUser user = merchantUser(request);
        if (user == null) return unauthorized();
        if (input == null || blank(input.senderId()))
            return bad("Sender ID or number is required.");
        if (blank(input.useCase())) return bad("Describe the sender ID purpose or use case.");
        String type =
                blank(input.senderType())
                        ? "ALPHANUMERIC"
                        : input.senderType().trim().toUpperCase(Locale.ROOT);
        if (!List.of("ALPHANUMERIC", "LONG_NUMBER", "SHORT_CODE").contains(type)) {
            return bad("Unsupported sender type.");
        }
        jdbcTemplate.update(
                "INSERT INTO communication_sender_identities"
                        + " (merchant_id,sender_id,sender_type,provider_code,country_code,use_case,supporting_document_ref,approval_status,two_way_capable,notes)"
                        + " VALUES (:merchant,:sender,:type,:provider,:country,:useCase,:documentRef,'PENDING','N',:notes)"
                        + " ON DUPLICATE KEY UPDATE sender_type=VALUES(sender_type),provider_code=VALUES(provider_code),"
                        + " country_code=VALUES(country_code),use_case=VALUES(use_case),supporting_document_ref=VALUES(supporting_document_ref),"
                        + " notes=VALUES(notes),approval_status=IF(approval_status='REJECTED','PENDING',approval_status),updated_at=NOW()",
                new MapSqlParameterSource()
                        .addValue("merchant", merchantId(user))
                        .addValue("sender", input.senderId().trim())
                        .addValue("type", type)
                        .addValue(
                                "provider",
                                blank(input.providerCode())
                                        ? null
                                        : input.providerCode().trim().toUpperCase(Locale.ROOT))
                        .addValue(
                                "country",
                                blank(input.countryCode())
                                        ? null
                                        : input.countryCode().trim().toUpperCase(Locale.ROOT))
                        .addValue("useCase", input.useCase().trim())
                        .addValue(
                                "documentRef",
                                blank(input.supportingDocumentRef())
                                        ? null
                                        : input.supportingDocumentRef().trim())
                        .addValue("notes", blank(input.notes()) ? null : input.notes().trim()));
        return ResponseEntity.accepted()
                .body(Map.of("requested", true, "approvalStatus", "PENDING"));
    }

    private LinkedHashMap<String, CampaignRecipient> expandAudience(
            long merchantId, CampaignRequest input) {
        LinkedHashMap<String, CampaignRecipient> unique = new LinkedHashMap<>();
        if (input.recipients() != null) {
            for (CampaignRecipient row : input.recipients()) {
                if (row == null) continue;
                String phone = normalizePhone(row.phone());
                unique.putIfAbsent(
                        phone,
                        new CampaignRecipient(
                                phone, row.variables() == null ? Map.of() : row.variables()));
            }
        }
        if (input.contactIds() != null && !input.contactIds().isEmpty()) {
            List<Map<String, Object>> contacts =
                    jdbcTemplate.queryForList(
                            "SELECT phone_e164,display_name FROM communication_contacts"
                                    + " WHERE merchant_id=:merchant AND active_flag='Y' AND id IN (:ids)",
                            new MapSqlParameterSource()
                                    .addValue("merchant", merchantId)
                                    .addValue("ids", input.contactIds()));
            for (Map<String, Object> row : contacts) {
                String phone = normalizePhone(String.valueOf(row.get("phone_e164")));
                String name =
                        row.get("display_name") == null
                                ? ""
                                : String.valueOf(row.get("display_name"));
                unique.putIfAbsent(phone, new CampaignRecipient(phone, contactVariables(name)));
            }
        }
        if (input.groupIds() != null && !input.groupIds().isEmpty()) {
            List<Map<String, Object>> members =
                    jdbcTemplate.queryForList(
                            "SELECT c.phone_e164,c.display_name FROM communication_contact_group_members gm"
                                    + " JOIN communication_contact_groups g ON g.id=gm.group_id"
                                    + " JOIN communication_contacts c ON c.id=gm.contact_id"
                                    + " WHERE g.merchant_id=:merchant AND c.merchant_id=:merchant AND c.active_flag='Y'"
                                    + " AND gm.group_id IN (:groups)",
                            new MapSqlParameterSource()
                                    .addValue("merchant", merchantId)
                                    .addValue("groups", input.groupIds()));
            for (Map<String, Object> row : members) {
                String phone = normalizePhone(String.valueOf(row.get("phone_e164")));
                String name =
                        row.get("display_name") == null
                                ? ""
                                : String.valueOf(row.get("display_name"));
                unique.putIfAbsent(phone, new CampaignRecipient(phone, contactVariables(name)));
            }
        }
        return unique;
    }

    private Map<String, String> contactVariables(String displayName) {
        if (blank(displayName)) return Map.of();
        String firstName = displayName.trim().split("\\s+", 2)[0];
        return Map.of("name", displayName.trim(), "first_name", firstName);
    }

    private boolean hasAudience(CampaignRequest input) {
        return (input.recipients() != null && !input.recipients().isEmpty())
                || (input.contactIds() != null && !input.contactIds().isEmpty())
                || (input.groupIds() != null && !input.groupIds().isEmpty());
    }

    private void insertCampaignItem(
            long campaignId,
            String phone,
            String messageReference,
            String body,
            String status,
            String trace) {
        jdbcTemplate.update(
                "INSERT INTO communication_campaign_items"
                        + " (campaign_id,recipient,message_reference,message_body,status,trace)"
                        + " VALUES (:campaign,:recipient,:messageReference,:body,:status,:trace)",
                new MapSqlParameterSource()
                        .addValue("campaign", campaignId)
                        .addValue("recipient", phone)
                        .addValue("messageReference", messageReference)
                        .addValue("body", body)
                        .addValue("status", status)
                        .addValue("trace", trace));
    }

    private String personalize(String template, Map<String, String> variables) {
        String result = template;
        if (variables == null || variables.isEmpty()) return result;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            if (entry.getKey() == null) continue;
            String value = entry.getValue() == null ? "" : entry.getValue();
            result = result.replace("{{" + entry.getKey().trim() + "}}", value);
        }
        return result;
    }

    private boolean isSuppressed(long merchantId, String phone) {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM communication_sms_suppressions WHERE merchant_id=:merchant"
                                + " AND phone_e164=:phone AND scope='MARKETING' AND active_flag='Y'",
                        new MapSqlParameterSource()
                                .addValue("merchant", merchantId)
                                .addValue("phone", phone),
                        Integer.class);
        return count != null && count > 0;
    }

    private MerchantUser merchantUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        Object value = session.getAttribute("merchantUser");
        return value instanceof MerchantUser user ? user : null;
    }

    private long merchantId(MerchantUser user) {
        Long value = user.getMerchant_id();
        if (value == null || value <= 0)
            throw new IllegalStateException("Merchant session has no valid merchant id.");
        return value;
    }

    private String userLabel(MerchantUser user) {
        if (user.getId() != null) return String.valueOf(user.getId());
        if (!blank(user.getEmail())) return user.getEmail();
        return "merchant-user";
    }

    private Timestamp scheduleTimestamp(String value) {
        if (blank(value)) return null;
        try {
            return Timestamp.from(OffsetDateTime.parse(value.trim()).toInstant());
        } catch (DateTimeParseException e) {
            try {
                return Timestamp.valueOf(value.trim().replace('T', ' '));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Invalid campaign schedule.");
            }
        }
    }

    private String normalizePhone(String value) {
        if (blank(value)) throw new IllegalArgumentException("Recipient phone is required.");
        String normalized = value.trim().replaceAll("[\\s()-]", "");
        if (!normalized.matches("\\+?[0-9]{7,15}")) {
            throw new IllegalArgumentException("Invalid recipient phone: " + value);
        }
        return normalized;
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("code", "MERCHANT_SESSION_REQUIRED"));
    }

    private ResponseEntity<?> bad(String message) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "INVALID_SMS_OPERATION_REQUEST", "message", message));
    }

    public record CampaignRecipient(String phone, Map<String, String> variables) {}

    public record CampaignRequest(
            String name,
            List<CampaignRecipient> recipients,
            List<Long> contactIds,
            List<Long> groupIds,
            String content,
            String purpose,
            Integer expiresInSeconds,
            String senderId,
            String scheduledAt,
            String routingStrategy,
            String countryCode,
            String currencyCode,
            Boolean requireDeliveryReceipts,
            Boolean requireInbound,
            Boolean fallbackEnabled) {}

    public record SenderEvidenceRequest(
            String senderId,
            String senderType,
            String providerCode,
            String countryCode,
            String useCase,
            String supportingDocumentRef,
            String notes) {}
}
