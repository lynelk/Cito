package net.citotech.cito.communication;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.citotech.cito.Common;
import net.citotech.cito.Model.MerchantUser;
import net.citotech.cito.communication.sms.SmsConversationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Session-authenticated SMS workspace used by the merchant portal. */
@RestController
@RequestMapping("/api/v2/merchant/communication/sms")
public class MerchantSmsWorkspaceController {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final MerchantCommunicationService communicationService;
    private final SmsConversationService conversationService;

    public MerchantSmsWorkspaceController(
            NamedParameterJdbcTemplate jdbcTemplate,
            MerchantCommunicationService communicationService,
            SmsConversationService conversationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.communicationService = communicationService;
        this.conversationService = conversationService;
    }

    @GetMapping("/overview")
    public ResponseEntity<?> overview(HttpServletRequest request) {
        MerchantUser user = merchantUser(request);
        if (user == null) return unauthorized();
        long merchantId = merchantId(user);
        Map<String, Object> counts =
                jdbcTemplate.queryForMap(
                        "SELECT"
                                + " (SELECT COUNT(*) FROM communication_messages WHERE merchant_id=:merchant AND selected_channel='SMS') totalMessages,"
                                + " (SELECT COUNT(*) FROM communication_messages WHERE merchant_id=:merchant AND selected_channel='SMS' AND status='SCHEDULED') scheduledMessages,"
                                + " (SELECT COUNT(*) FROM communication_contacts WHERE merchant_id=:merchant AND active_flag='Y') contacts,"
                                + " (SELECT COUNT(*) FROM communication_contact_groups WHERE merchant_id=:merchant) contactGroups,"
                                + " (SELECT COUNT(*) FROM communication_conversations WHERE merchant_id=:merchant AND unread_count>0) unreadConversations,"
                                + " (SELECT COUNT(*) FROM communication_sender_identities WHERE merchant_id=:merchant AND approval_status='APPROVED') approvedSenders",
                        new MapSqlParameterSource("merchant", merchantId));
        List<Map<String, Object>> recent = historyRows(merchantId, 8, null);
        return ResponseEntity.ok(Map.of("metrics", counts, "recent", recent));
    }

    @PostMapping(path = "/analyze", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> analyze(
            @RequestBody AnalyzeRequest input, HttpServletRequest request) {
        if (merchantUser(request) == null) return unauthorized();
        return ResponseEntity.ok(
                communicationService.analyzeSms(input == null ? "" : input.content()));
    }

    @PostMapping(path = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> preview(
            @RequestBody PreviewRequest input, HttpServletRequest request) {
        MerchantUser user = merchantUser(request);
        if (user == null) return unauthorized();
        try {
            return ResponseEntity.ok(
                    communicationService.previewSms(
                            merchantId(user),
                            input.content(),
                            input.senderId(),
                            input.countryCode(),
                            input.currencyCode(),
                            input.routingStrategy(),
                            Boolean.TRUE.equals(input.requireDeliveryReceipts()),
                            Boolean.TRUE.equals(input.requireInbound())));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @PostMapping(path = "/send", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> send(
            @RequestBody WorkspaceSendRequest input, HttpServletRequest request) {
        MerchantUser user = merchantUser(request);
        if (user == null) return unauthorized();
        long merchantId = merchantId(user);
        try {
            Set<String> recipients = resolveRecipients(merchantId, input);
            if (recipients.isEmpty())
                throw new IllegalArgumentException("Add at least one recipient, contact or group.");
            if (recipients.size() > 1000)
                throw new IllegalArgumentException("A send can contain at most 1,000 recipients.");
            List<String> allowed = new ArrayList<>();
            List<String> suppressed = new ArrayList<>();
            for (String recipient : recipients) {
                if ("MARKETING".equalsIgnoreCase(input.purpose())
                        && isSuppressed(merchantId, recipient)) suppressed.add(recipient);
                else allowed.add(recipient);
            }
            List<Map<String, Object>> accepted = new ArrayList<>();
            String batchReference = "SMS-" + Common.randomUrlSafeToken(12);
            for (int i = 0; i < allowed.size(); i++) {
                accepted.add(
                        communicationService.enqueueSms(
                                merchantId,
                                allowed.get(i),
                                input.content(),
                                input.purpose(),
                                batchReference + ":" + i,
                                batchReference + ":" + i,
                                input.expiresInSeconds(),
                                new MerchantCommunicationService.SmsOptions(
                                        input.senderId(),
                                        input.scheduledAt(),
                                        input.routingStrategy(),
                                        input.countryCode(),
                                        input.currencyCode(),
                                        Boolean.TRUE.equals(input.requireDeliveryReceipts()),
                                        Boolean.TRUE.equals(input.requireInbound()),
                                        input.fallbackEnabled() == null
                                                || input.fallbackEnabled())));
            }
            return ResponseEntity.accepted()
                    .body(
                            Map.of(
                                    "batchReference", batchReference,
                                    "accepted", accepted.size(),
                                    "suppressed", suppressed.size(),
                                    "suppressedRecipients", suppressed,
                                    "messages", accepted));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @GetMapping("/history")
    public ResponseEntity<?> history(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            HttpServletRequest request) {
        MerchantUser user = merchantUser(request);
        if (user == null) return unauthorized();
        return ResponseEntity.ok(historyRows(merchantId(user), limit, status));
    }

    @GetMapping("/contacts")
    public ResponseEntity<?> contacts(HttpServletRequest request) {
        MerchantUser user = merchantUser(request);
        if (user == null) return unauthorized();
        return ResponseEntity.ok(
                jdbcTemplate.queryForList(
                        "SELECT id,display_name displayName,phone_e164 phone,email,attributes_json attributes,active_flag active,"
                                + " created_at createdAt,updated_at updatedAt FROM communication_contacts"
                                + " WHERE merchant_id=:merchant ORDER BY display_name ASC",
                        new MapSqlParameterSource("merchant", merchantId(user))));
    }

    @PostMapping(path = "/contacts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> saveContact(
            @RequestBody ContactRequest input, HttpServletRequest request) {
        MerchantUser user = merchantUser(request);
        if (user == null) return unauthorized();
        try {
            if (input == null || blank(input.displayName()))
                throw new IllegalArgumentException("Contact name is required.");
            String phone = normalizePhone(input.phone());
            jdbcTemplate.update(
                    "INSERT INTO communication_contacts (merchant_id,display_name,phone_e164,email,attributes_json,active_flag)"
                            + " VALUES (:merchant,:name,:phone,:email,:attributes,'Y')"
                            + " ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),email=VALUES(email),"
                            + " attributes_json=VALUES(attributes_json),active_flag='Y',updated_at=NOW()",
                    new MapSqlParameterSource()
                            .addValue("merchant", merchantId(user))
                            .addValue("name", input.displayName().trim())
                            .addValue("phone", phone)
                            .addValue("email", blank(input.email()) ? null : input.email().trim())
                            .addValue(
                                    "attributes",
                                    blank(input.attributesJson()) ? null : input.attributesJson()));
            return ResponseEntity.ok(Map.of("saved", true, "phone", phone));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @DeleteMapping("/contacts/{id}")
    public ResponseEntity<?> deleteContact(@PathVariable long id, HttpServletRequest request) {
        MerchantUser user = merchantUser(request);
        if (user == null) return unauthorized();
        int changed =
                jdbcTemplate.update(
                        "UPDATE communication_contacts SET active_flag='N' WHERE id=:id AND merchant_id=:merchant",
                        new MapSqlParameterSource()
                                .addValue("id", id)
                                .addValue("merchant", merchantId(user)));
        return ResponseEntity.ok(Map.of("deleted", changed > 0));
    }

    @GetMapping("/groups")
    public ResponseEntity<?> groups(HttpServletRequest request) {
        MerchantUser user = merchantUser(request);
        if (user == null) return unauthorized();
        return ResponseEntity.ok(
                jdbcTemplate.queryForList(
                        "SELECT g.id,g.group_name groupName,g.description,COUNT(m.contact_id) memberCount,g.updated_at updatedAt"
                                + " FROM communication_contact_groups g LEFT JOIN communication_contact_group_members m ON m.group_id=g.id"
                                + " WHERE g.merchant_id=:merchant GROUP BY g.id,g.group_name,g.description,g.updated_at"
                                + " ORDER BY g.group_name ASC",
                        new MapSqlParameterSource("merchant", merchantId(user))));
    }

    @PostMapping(path = "/groups", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createGroup(
            @RequestBody GroupRequest input, HttpServletRequest request) {
        MerchantUser user = merchantUser(request);
        if (user == null) return unauthorized();
        if (input == null || blank(input.groupName())) return bad("Group name is required.");
        jdbcTemplate.update(
                "INSERT INTO communication_contact_groups (merchant_id,group_name,description)"
                        + " VALUES (:merchant,:name,:description)"
                        + " ON DUPLICATE KEY UPDATE description=VALUES(description),updated_at=NOW()",
                new MapSqlParameterSource()
                        .addValue("merchant", merchantId(user))
                        .addValue("name", input.groupName().trim())
                        .addValue(
                                "description",
                                blank(input.description()) ? null : input.description().trim()));
        return ResponseEntity.ok(Map.of("saved", true));
    }

    @PostMapping(path = "/groups/{groupId}/members", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> groupMembers(
            @PathVariable long groupId,
            @RequestBody GroupMembersRequest input,
            HttpServletRequest request) {
        MerchantUser user = merchantUser(request);
        if (user == null) return unauthorized();
        long merchantId = merchantId(user);
        if (!ownsGroup(merchantId, groupId)) return bad("Contact group was not found.");
        int added = 0;
        if (input != null && input.contactIds() != null) {
            for (Long contactId : input.contactIds()) {
                if (contactId == null || !ownsContact(merchantId, contactId)) continue;
                jdbcTemplate.update(
                        "INSERT IGNORE INTO communication_contact_group_members (group_id,contact_id) VALUES (:group,:contact)",
                        new MapSqlParameterSource()
                                .addValue("group", groupId)
                                .addValue("contact", contactId));
                added++;
            }
        }
        return ResponseEntity.ok(Map.of("added", added));
    }

    @GetMapping("/sender-identities")
    public ResponseEntity<?> senderIdentities(HttpServletRequest request) {
        MerchantUser user = merchantUser(request);
        if (user == null) return unauthorized();
        return ResponseEntity.ok(
                jdbcTemplate.queryForList(
                        "SELECT id,sender_id senderId,sender_type senderType,provider_code providerCode,country_code countryCode,"
                                + " approval_status approvalStatus,two_way_capable twoWayCapable,default_flag defaultFlag,notes,updated_at updatedAt"
                                + " FROM communication_sender_identities WHERE merchant_id=:merchant"
                                + " ORDER BY default_flag DESC,approval_status ASC,sender_id ASC",
                        new MapSqlParameterSource("merchant", merchantId(user))));
    }

    @PostMapping(path = "/sender-identities", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> requestSenderIdentity(
            @RequestBody SenderIdentityRequest input, HttpServletRequest request) {
        MerchantUser user = merchantUser(request);
        if (user == null) return unauthorized();
        if (input == null || blank(input.senderId()))
            return bad("Sender ID or number is required.");
        String type =
                blank(input.senderType())
                        ? "ALPHANUMERIC"
                        : input.senderType().trim().toUpperCase();
        if (!List.of("ALPHANUMERIC", "LONG_NUMBER", "SHORT_CODE").contains(type))
            return bad("Unsupported sender type.");
        String token =
                "LONG_NUMBER".equals(type) || "SHORT_CODE".equals(type)
                        ? Common.randomUrlSafeToken(32)
                        : null;
        jdbcTemplate.update(
                "INSERT INTO communication_sender_identities"
                        + " (merchant_id,sender_id,sender_type,provider_code,country_code,approval_status,two_way_capable,inbound_token,notes)"
                        + " VALUES (:merchant,:sender,:type,:provider,:country,'PENDING','N',:token,:notes)"
                        + " ON DUPLICATE KEY UPDATE sender_type=VALUES(sender_type),country_code=VALUES(country_code),"
                        + " notes=VALUES(notes),approval_status=IF(approval_status='REJECTED','PENDING',approval_status),updated_at=NOW()",
                new MapSqlParameterSource()
                        .addValue("merchant", merchantId(user))
                        .addValue("sender", input.senderId().trim())
                        .addValue("type", type)
                        .addValue(
                                "provider",
                                blank(input.providerCode())
                                        ? null
                                        : input.providerCode().trim().toUpperCase())
                        .addValue(
                                "country",
                                blank(input.countryCode())
                                        ? null
                                        : input.countryCode().trim().toUpperCase())
                        .addValue("token", token)
                        .addValue("notes", blank(input.notes()) ? null : input.notes().trim()));
        return ResponseEntity.accepted()
                .body(Map.of("requested", true, "approvalStatus", "PENDING"));
    }

    @GetMapping("/templates")
    public ResponseEntity<?> templates(HttpServletRequest request) {
        if (merchantUser(request) == null) return unauthorized();
        return ResponseEntity.ok(
                jdbcTemplate.queryForList(
                        "SELECT template_key templateKey,body_template body,status,updated_at updatedAt"
                                + " FROM communication_message_templates WHERE channel='SMS' AND status='ACTIVE' ORDER BY template_key",
                        new MapSqlParameterSource()));
    }

    @GetMapping("/drafts")
    public ResponseEntity<?> drafts(HttpServletRequest request) {
        MerchantUser user = merchantUser(request);
        if (user == null) return unauthorized();
        return ResponseEntity.ok(
                jdbcTemplate.queryForList(
                        "SELECT public_id draftId,title,recipient_mode recipientMode,recipient_payload_json recipientPayload,"
                                + " sender_id senderId,purpose,message_body messageBody,routing_strategy routingStrategy,"
                                + " scheduled_at scheduledAt,updated_at updatedAt FROM communication_sms_drafts"
                                + " WHERE merchant_id=:merchant ORDER BY updated_at DESC",
                        new MapSqlParameterSource("merchant", merchantId(user))));
    }

    @PostMapping(path = "/drafts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> saveDraft(
            @RequestBody DraftRequest input, HttpServletRequest request) {
        MerchantUser user = merchantUser(request);
        if (user == null) return unauthorized();
        if (input == null || blank(input.messageBody()))
            return bad("Draft message body is required.");
        String publicId =
                blank(input.draftId())
                        ? "DRF-" + Common.randomUrlSafeToken(18)
                        : input.draftId().trim();
        jdbcTemplate.update(
                "INSERT INTO communication_sms_drafts"
                        + " (public_id,merchant_id,title,recipient_mode,recipient_payload_json,sender_id,purpose,message_body,"
                        + " routing_strategy,scheduled_at,created_by)"
                        + " VALUES (:public_id,:merchant,:title,:recipient_mode,:recipient_payload,:sender,:purpose,:body,"
                        + " :routing_strategy,:scheduled_at,:created_by)"
                        + " ON DUPLICATE KEY UPDATE title=VALUES(title),recipient_mode=VALUES(recipient_mode),"
                        + " recipient_payload_json=VALUES(recipient_payload_json),sender_id=VALUES(sender_id),"
                        + " purpose=VALUES(purpose),message_body=VALUES(message_body),routing_strategy=VALUES(routing_strategy),"
                        + " scheduled_at=VALUES(scheduled_at),updated_at=NOW()",
                new MapSqlParameterSource()
                        .addValue("public_id", publicId)
                        .addValue("merchant", merchantId(user))
                        .addValue("title", blank(input.title()) ? null : input.title().trim())
                        .addValue(
                                "recipient_mode",
                                blank(input.recipientMode())
                                        ? "DIRECT"
                                        : input.recipientMode().trim().toUpperCase())
                        .addValue(
                                "recipient_payload",
                                blank(input.recipientPayloadJson())
                                        ? null
                                        : input.recipientPayloadJson())
                        .addValue(
                                "sender", blank(input.senderId()) ? null : input.senderId().trim())
                        .addValue(
                                "purpose",
                                blank(input.purpose())
                                        ? "NOTIFICATION"
                                        : input.purpose().trim().toUpperCase())
                        .addValue("body", input.messageBody())
                        .addValue(
                                "routing_strategy",
                                blank(input.routingStrategy())
                                        ? "BALANCED"
                                        : input.routingStrategy().trim().toUpperCase())
                        .addValue("scheduled_at", null)
                        .addValue("created_by", user.getEmail()));
        return ResponseEntity.ok(Map.of("saved", true, "draftId", publicId));
    }

    @DeleteMapping("/drafts/{draftId}")
    public ResponseEntity<?> deleteDraft(@PathVariable String draftId, HttpServletRequest request) {
        MerchantUser user = merchantUser(request);
        if (user == null) return unauthorized();
        int deleted =
                jdbcTemplate.update(
                        "DELETE FROM communication_sms_drafts WHERE public_id=:draft AND merchant_id=:merchant",
                        new MapSqlParameterSource()
                                .addValue("draft", draftId)
                                .addValue("merchant", merchantId(user)));
        return ResponseEntity.ok(Map.of("deleted", deleted > 0));
    }

    @GetMapping("/conversations")
    public ResponseEntity<?> conversations(
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            HttpServletRequest request) {
        MerchantUser user = merchantUser(request);
        if (user == null) return unauthorized();
        return ResponseEntity.ok(conversationService.conversations(merchantId(user), limit));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<?> conversationMessages(
            @PathVariable String conversationId,
            @RequestParam(name = "limit", defaultValue = "200") int limit,
            HttpServletRequest request) {
        MerchantUser user = merchantUser(request);
        if (user == null) return unauthorized();
        try {
            return ResponseEntity.ok(
                    conversationService.messages(merchantId(user), conversationId, limit));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @PostMapping(
            path = "/conversations/{conversationId}/reply",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> reply(
            @PathVariable String conversationId,
            @RequestBody ReplyRequest input,
            HttpServletRequest request) {
        MerchantUser user = merchantUser(request);
        if (user == null) return unauthorized();
        long merchantId = merchantId(user);
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT c.phone_e164 phone,s.sender_id senderId,c.provider_code providerCode"
                                + " FROM communication_conversations c LEFT JOIN communication_sender_identities s ON s.id=c.sender_identity_id"
                                + " WHERE c.public_id=:conversation AND c.merchant_id=:merchant LIMIT 1",
                        new MapSqlParameterSource()
                                .addValue("conversation", conversationId)
                                .addValue("merchant", merchantId));
        if (rows.isEmpty()) return bad("Conversation was not found.");
        if (input == null || blank(input.content())) return bad("Reply message is required.");
        Map<String, Object> row = rows.get(0);
        String senderId = row.get("senderId") == null ? null : String.valueOf(row.get("senderId"));
        try {
            Map<String, Object> message =
                    communicationService.enqueueSms(
                            merchantId,
                            String.valueOf(row.get("phone")),
                            input.content(),
                            "NOTIFICATION",
                            "CONV-" + conversationId + "-" + Common.randomUrlSafeToken(8),
                            null,
                            86400,
                            new MerchantCommunicationService.SmsOptions(
                                    senderId, null, "BALANCED", null, "UGX", true, true, true));
            jdbcTemplate.update(
                    "INSERT INTO communication_conversation_messages"
                            + " (public_id,conversation_id,merchant_id,direction,provider_code,message_reference,"
                            + " from_address,to_address,body,status)"
                            + " SELECT :public_id,id,:merchant,'OUTBOUND',:provider,:message_reference,:from_address,phone_e164,:body,'QUEUED'"
                            + " FROM communication_conversations WHERE public_id=:conversation AND merchant_id=:merchant",
                    new MapSqlParameterSource()
                            .addValue("public_id", "MSG-" + Common.randomUrlSafeToken(18))
                            .addValue("merchant", merchantId)
                            .addValue("provider", row.get("providerCode"))
                            .addValue("message_reference", message.get("messageReference"))
                            .addValue("from_address", senderId)
                            .addValue("body", input.content())
                            .addValue("conversation", conversationId));
            return ResponseEntity.accepted().body(message);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    private List<Map<String, Object>> historyRows(long merchantId, int limit, String status) {
        String filter = blank(status) ? "" : " AND status=:status";
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("merchant", merchantId)
                        .addValue("limit", Math.max(1, Math.min(limit, 500)));
        if (!blank(status)) params.addValue("status", status.trim().toUpperCase());
        return jdbcTemplate.queryForList(
                "SELECT public_id messageReference,recipient,purpose,status,selected_provider_code provider,"
                        + " scheduled_at scheduledAt,created_at createdAt,updated_at updatedAt,"
                        + " JSON_UNQUOTE(JSON_EXTRACT(metadata_json,'$.senderId')) senderId,"
                        + " JSON_UNQUOTE(JSON_EXTRACT(metadata_json,'$.segments')) segments,"
                        + " JSON_UNQUOTE(JSON_EXTRACT(metadata_json,'$.encoding')) encoding"
                        + " FROM communication_messages WHERE merchant_id=:merchant AND selected_channel='SMS'"
                        + filter
                        + " ORDER BY created_at DESC LIMIT :limit",
                params);
    }

    private Set<String> resolveRecipients(long merchantId, WorkspaceSendRequest input) {
        if (input == null) throw new IllegalArgumentException("SMS request is required.");
        Set<String> recipients = new LinkedHashSet<>();
        if (input.recipients() != null)
            for (String value : input.recipients()) recipients.add(normalizePhone(value));
        if (input.contactIds() != null && !input.contactIds().isEmpty()) {
            for (Long id : input.contactIds()) {
                if (id == null) continue;
                List<String> phones =
                        jdbcTemplate.query(
                                "SELECT phone_e164 FROM communication_contacts WHERE id=:id AND merchant_id=:merchant AND active_flag='Y'",
                                new MapSqlParameterSource()
                                        .addValue("id", id)
                                        .addValue("merchant", merchantId),
                                (rs, rowNum) -> rs.getString(1));
                recipients.addAll(phones);
            }
        }
        if (input.groupIds() != null && !input.groupIds().isEmpty()) {
            for (Long groupId : input.groupIds()) {
                if (groupId == null || !ownsGroup(merchantId, groupId)) continue;
                recipients.addAll(
                        jdbcTemplate.query(
                                "SELECT c.phone_e164 FROM communication_contact_group_members gm"
                                        + " JOIN communication_contacts c ON c.id=gm.contact_id"
                                        + " WHERE gm.group_id=:group AND c.merchant_id=:merchant AND c.active_flag='Y'",
                                new MapSqlParameterSource()
                                        .addValue("group", groupId)
                                        .addValue("merchant", merchantId),
                                (rs, rowNum) -> rs.getString(1)));
            }
        }
        return recipients;
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

    private boolean ownsGroup(long merchantId, long groupId) {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM communication_contact_groups WHERE id=:id AND merchant_id=:merchant",
                        new MapSqlParameterSource()
                                .addValue("id", groupId)
                                .addValue("merchant", merchantId),
                        Integer.class);
        return count != null && count > 0;
    }

    private boolean ownsContact(long merchantId, long contactId) {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM communication_contacts WHERE id=:id AND merchant_id=:merchant AND active_flag='Y'",
                        new MapSqlParameterSource()
                                .addValue("id", contactId)
                                .addValue("merchant", merchantId),
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
        Object value = user.getMerchant_id();
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            throw new IllegalStateException("Merchant session has no valid merchant id.");
        }
    }

    private String normalizePhone(String value) {
        if (blank(value)) throw new IllegalArgumentException("Recipient phone is required.");
        String normalized = value.trim().replaceAll("[\\s()-]", "");
        if (!normalized.matches("\\+?[0-9]{7,15}"))
            throw new IllegalArgumentException("Invalid recipient phone: " + value);
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
                .body(Map.of("code", "INVALID_SMS_WORKSPACE_REQUEST", "message", message));
    }

    public record AnalyzeRequest(String content) {}

    public record PreviewRequest(
            String content,
            String senderId,
            String routingStrategy,
            String countryCode,
            String currencyCode,
            Boolean requireDeliveryReceipts,
            Boolean requireInbound) {}

    public record WorkspaceSendRequest(
            List<String> recipients,
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

    public record ContactRequest(
            String displayName, String phone, String email, String attributesJson) {}

    public record GroupRequest(String groupName, String description) {}

    public record GroupMembersRequest(List<Long> contactIds) {}

    public record SenderIdentityRequest(
            String senderId,
            String senderType,
            String providerCode,
            String countryCode,
            String notes) {}

    public record DraftRequest(
            String draftId,
            String title,
            String recipientMode,
            String recipientPayloadJson,
            String senderId,
            String purpose,
            String messageBody,
            String routingStrategy,
            String scheduledAt) {}

    public record ReplyRequest(String content) {}
}
