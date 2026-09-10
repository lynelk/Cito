package net.citotech.cito.merchant;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.Model.MerchantUser;
import net.citotech.cito.gateway.AirtelOpenApiCredentialSchema;
import net.citotech.cito.gateway.MtnMomoCredentialSchema;
import net.citotech.cito.gateway.PaymentChannelAdapter;
import net.citotech.cito.gateway.PaymentChannelRegistry;
import net.citotech.cito.gateway.PaymentGatewayException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MerchantChannelCredentialService {
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PaymentChannelRegistry registry;
    private final MerchantChannelCryptoService cryptoService;
    private final MerchantEnvironmentService environmentService;
    private final ObjectMapper objectMapper;

    public MerchantChannelCredentialService(
            NamedParameterJdbcTemplate jdbcTemplate,
            PaymentChannelRegistry registry,
            MerchantChannelCryptoService cryptoService,
            MerchantEnvironmentService environmentService,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.registry = registry;
        this.cryptoService = cryptoService;
        this.environmentService = environmentService;
        this.objectMapper = objectMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private net.citotech.cito.gateway.ProviderCredentialProbeService probe;

    public List<Map<String, Object>> list(MerchantUser user) {
        requireUser(user);
        Map<String, Object> preference = environmentService.getPreference(user);
        String activeEnvironment = text(preference.get("environment"));
        List<Map<String, Object>> response = new ArrayList<>();
        for (PaymentChannelAdapter adapter : registry.getAdapters()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("channelCode", adapter.channelCode());
            item.put("displayName", adapter.displayName());
            item.put("countryCode", adapter.countryCode());
            item.put("currencyCode", adapter.currencyCode());
            item.put("supported", true);
            item.put("status", "NOT_CONFIGURED");
            item.put("environment", activeEnvironment);
            item.put("credentials", new LinkedHashMap<String, Object>());
            item.put("sandboxCredentials", sandboxCredentials(adapter));
            item.put("sandboxGuide", preference.get("sandbox"));

            Map<String, Object> environments = new LinkedHashMap<>();
            environments.put(
                    "SANDBOX", environmentRecord(adapter, user.getMerchant_id(), "SANDBOX"));
            environments.put(
                    "PRODUCTION", environmentRecord(adapter, user.getMerchant_id(), "PRODUCTION"));
            item.put("environments", environments);

            Map<String, Object> active = asMapOrEmpty(environments.get(activeEnvironment));
            item.putAll(active);
            item.put("environment", activeEnvironment);
            if (!active.containsKey("credentials")) {
                item.put("credentials", new LinkedHashMap<String, Object>());
            }
            response.add(item);
        }
        return response;
    }

    private Map<String, Object> environmentRecord(
            PaymentChannelAdapter adapter, Long merchantId, String environment) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("channelCode", adapter.channelCode());
        record.put("environment", environment);
        record.put("displayName", adapter.displayName());
        record.put("status", "NOT_CONFIGURED");
        record.put("credentials", new LinkedHashMap<String, Object>());
        Map<String, Object> saved = find(merchantId, adapter.channelCode(), environment);
        if (saved != null) record.putAll(saved);
        return record;
    }

    private Map<String, Object> sandboxCredentials(PaymentChannelAdapter adapter) {
        Map<String, Object> credentials = new LinkedHashMap<>();
        String channelCode = adapter.channelCode();
        if (MtnMomoCredentialSchema.CHANNEL_CODE.equalsIgnoreCase(channelCode)) {
            credentials.put("baseUrl", MtnMomoCredentialSchema.SANDBOX_BASE_URL);
            credentials.put("targetEnvironment", "sandbox");
            credentials.put("baseCurrency", "EUR");
            credentials.put("callbackHost", "");
            credentials.put("callbackUrl", "");
            credentials.put("collectionApiUser", "");
            credentials.put("collectionApiKey", "");
            credentials.put("collectionSubscriptionKey", "");
            credentials.put("collectionSecondarySubscriptionKey", "");
            credentials.put("disbursementApiUser", "");
            credentials.put("disbursementApiKey", "");
            credentials.put("disbursementSubscriptionKey", "");
            credentials.put("disbursementSecondarySubscriptionKey", "");
            return credentials;
        }
        if (AirtelOpenApiCredentialSchema.CHANNEL_CODE.equalsIgnoreCase(channelCode)) {
            credentials.put("baseUrl", AirtelOpenApiCredentialSchema.SANDBOX_BASE_URL);
            credentials.put("clientId", "");
            credentials.put("clientSecret", "");
            credentials.put("country", "UG");
            credentials.put("currency", "UGX");
            credentials.put("apiPin", "");
            credentials.put("publicKey", "");
            credentials.put("tokenPath", "/auth/oauth2/token");
            credentials.put("collectionPath", "/merchant/v2/payments/");
            credentials.put("payoutPath", "/standard/v2/disbursements/");
            credentials.put("balancePath", "/standard/v2/users/balance");
            return credentials;
        }
        credentials.put("collectUrl", "");
        credentials.put("payoutUrl", "");
        credentials.put("authHeaderName", "X-CPay-Sandbox-Key");
        credentials.put("authHeaderValue", "sandbox-test-key");
        if ("safaricom_mpesa".equalsIgnoreCase(channelCode)) {
            credentials.put("shortCode", "174379");
            credentials.put("consumerKey", "sandbox-consumer-key");
            credentials.put("consumerSecret", "sandbox-consumer-secret");
            credentials.put("passKey", "sandbox-pass-key");
        } else if ("airtel_open_api".equalsIgnoreCase(channelCode)) {
            credentials.put("clientId", "sandbox-client-id");
            credentials.put("clientSecret", "sandbox-client-secret");
            credentials.put("subscriberMsisdn", "256770000001");
        } else if ("yo_payments".equalsIgnoreCase(channelCode)) {
            credentials.put("apiUser", "yo-sandbox-user");
            credentials.put("apiKey", "yo-sandbox-key");
            credentials.put("collectionAccount", "YO-SANDBOX");
        } else {
            credentials.put("apiUser", channelCode + "-sandbox-user");
            credentials.put("apiKey", channelCode + "-sandbox-key");
            credentials.put("collectionAccount", "SANDBOX-COLLECTION");
        }
        return credentials;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMapOrEmpty(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return new LinkedHashMap<>();
    }

    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> save(MerchantUser user, Map<String, Object> body) {
        requireUser(user);
        requireCanManageChannels(user);
        String channelCode = text(body.get("channelCode"));
        String environment = normalizedEnvironment(text(body.get("environment")));
        jdbcTemplate.queryForObject(
                "SELECT id FROM merchants WHERE id=:id FOR UPDATE",
                new MapSqlParameterSource("id", user.getMerchant_id()),
                Long.class);
        Map<String, Object> saved = find(user.getMerchant_id(), channelCode, environment);
        long revision = saved == null ? 0 : ((Number) saved.get("revision")).longValue();
        if (saved != null
                && (!(body.get("revision") instanceof Number)
                        || ((Number) body.get("revision")).longValue() != revision))
            throw new PaymentGatewayException(
                    "Credential configuration changed; reload before saving");
        Map<String, Object> credentials =
                CredentialEdits.merge(
                        saved == null
                                ? Map.of()
                                : loadDecrypted(user.getMerchant_id(), channelCode, environment),
                        asMap(body.get("credentials")),
                        body.get("clearFields") instanceof List<?> list ? list : List.of());
        PaymentChannelAdapter adapter =
                registry.findByChannelCode(channelCode)
                        .orElseThrow(
                                () ->
                                        new PaymentGatewayException(
                                                "Unsupported channel: " + channelCode));
        validateRequiredCredentials(adapter, credentials, environment);
        String payload = toJson(credentials);
        String encrypted = cryptoService.encrypt(payload);
        String mask = toJson(mask(credentials));
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("merchant_id", user.getMerchant_id());
        p.addValue("channel_code", adapter.channelCode());
        p.addValue("environment", environment);
        p.addValue("display_name", adapter.displayName());
        p.addValue("credential_payload", encrypted);
        p.addValue("credential_mask", mask);
        p.addValue("status", "CONFIGURED");
        p.addValue("actor", user.getEmail());
        String sql =
                "INSERT INTO merchant_channel_credentials (merchant_id, channel_code, environment,"
                        + " display_name, credential_payload, credential_mask, status, created_by,"
                        + " updated_by) VALUES (:merchant_id, :channel_code, :environment,"
                        + " :display_name, :credential_payload, :credential_mask, :status, :actor,"
                        + " :actor) ON DUPLICATE KEY UPDATE display_name=:display_name,"
                        + " credential_payload=:credential_payload, credential_mask=:credential_mask,"
                        + " status=:status, revision=revision+1, last_test_status=NULL, last_test_message=NULL, last_tested_at=NULL, tested_revision=NULL, approved_by=NULL, approved_at=NULL, decision_reason=NULL, submitted_for_approval_at=NULL, updated_by=:actor, updated_at=CURRENT_TIMESTAMP";
        jdbcTemplate.update(sql, p);
        audit(
                user.getMerchant_id(),
                adapter.channelCode(),
                environment,
                "SAVED",
                user.getEmail(),
                "Merchant updated channel credentials");
        return find(user.getMerchant_id(), adapter.channelCode(), environment);
    }

    public Map<String, Object> test(MerchantUser user, Map<String, Object> body) {
        requireUser(user);
        requireCanManageChannels(user);
        String channelCode = text(body.get("channelCode"));
        String environment = normalizedEnvironment(text(body.get("environment")));
        Map<String, Object> saved = find(user.getMerchant_id(), channelCode, environment);
        if (saved == null)
            throw new PaymentGatewayException("Channel credentials are not configured");
        PaymentChannelAdapter adapter =
                registry.findByChannelCode(channelCode)
                        .orElseThrow(
                                () ->
                                        new PaymentGatewayException(
                                                "Unsupported channel: " + channelCode));
        Map<String, Object> credentials =
                loadDecrypted(user.getMerchant_id(), channelCode, environment);
        validateRequiredCredentials(adapter, credentials, environment);
        MapSqlParameterSource p = params(user.getMerchant_id(), channelCode, environment);
        boolean connectivity = Boolean.TRUE.equals(body.get("connectivity"));
        // Local structure checks preserve authentication evidence for the same revision.
        if (!connectivity && "CONNECTIVITY_VERIFIED".equals(saved.get("lastTestStatus")))
            return saved;
        if (connectivity) {
            try {
                probe.verify(
                        channelCode,
                        environment,
                        text(credentials.getOrDefault("country", adapter.countryCode())),
                        text(
                                credentials.getOrDefault(
                                        "baseCurrency",
                                        credentials.getOrDefault(
                                                "currency", adapter.currencyCode()))),
                        credentials);
            } catch (RuntimeException failure) {
                // Replace stale successful evidence only on the revision actually tested.
                p.addValue("revision", saved.get("revision"));
                jdbcTemplate.update(
                        "UPDATE merchant_channel_credentials SET last_test_status='CONNECTIVITY_FAILED',last_test_message='Provider authentication could not be verified',last_tested_at=CURRENT_TIMESTAMP,tested_revision=:revision WHERE merchant_id=:merchant_id AND channel_code=:channel_code AND environment=:environment AND revision=:revision",
                        p);
                audit(
                        user.getMerchant_id(),
                        channelCode,
                        environment,
                        "CONNECTIVITY_FAILED",
                        user.getEmail(),
                        "Provider authentication could not be verified");
                throw new PaymentGatewayException(
                        "Provider authentication could not be verified; check credentials and provider availability");
            }
        }
        p.addValue("revision", saved.get("revision"));
        p.addValue(
                "status",
                "ACTIVE".equals(saved.get("status"))
                        ? "ACTIVE"
                        : !"PRODUCTION".equals(environment)
                                        && (connectivity
                                                || !net.citotech.cito.gateway
                                                        .MobileMoneyExecutionService.managed(
                                                        channelCode))
                                ? "SANDBOX_TESTED"
                                : "CONFIGURED");
        p.addValue("test_status", connectivity ? "CONNECTIVITY_VERIFIED" : "STRUCTURE_VALID");
        p.addValue(
                "message",
                connectivity
                        ? "Provider authentication verified; payment, callback and settlement acceptance still require UAT"
                        : "Credential structure validated locally; no provider request was sent");
        int tested =
                jdbcTemplate.update(
                        "UPDATE merchant_channel_credentials SET status=:status,last_test_status=:test_status,last_test_message=:message,last_tested_at=CURRENT_TIMESTAMP,tested_revision=:revision WHERE merchant_id=:merchant_id AND channel_code=:channel_code AND environment=:environment AND revision=:revision",
                        p);
        if (tested != 1)
            throw new PaymentGatewayException(
                    "Credentials changed during verification; test the new revision");
        audit(
                user.getMerchant_id(),
                channelCode,
                environment,
                environment + "_TEST",
                user.getEmail(),
                "Merchant ran channel readiness test");
        return find(user.getMerchant_id(), channelCode, environment);
    }

    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> submitForApproval(MerchantUser user, Map<String, Object> body) {
        requireUser(user);
        requireCanManageChannels(user);
        String channelCode = text(body.get("channelCode"));
        String environment = normalizedEnvironment(text(body.get("environment")));
        MapSqlParameterSource p = params(user.getMerchant_id(), channelCode, environment);
        int updated =
                jdbcTemplate.update(
                        "UPDATE merchant_channel_credentials SET status='SUBMITTED_FOR_APPROVAL',"
                                + " submitted_for_approval_at=CURRENT_TIMESTAMP, updated_by=:actor"
                                + " WHERE merchant_id=:merchant_id AND channel_code=:channel_code AND"
                                + " environment=:environment AND status IN ('CONFIGURED','SANDBOX_TESTED','REJECTED') AND (channel_code NOT IN ('mtn_momo','airtel_open_api') OR (last_test_status='CONNECTIVITY_VERIFIED' AND tested_revision=revision))",
                        p.addValue("actor", user.getEmail()));
        if (updated < 1)
            throw new PaymentGatewayException(
                    "Verify provider connectivity for the current credential revision before submitting");
        audit(
                user.getMerchant_id(),
                channelCode,
                environment,
                "SUBMITTED_FOR_APPROVAL",
                user.getEmail(),
                "Merchant submitted channel for production approval");
        return find(user.getMerchant_id(), channelCode, environment);
    }

    public void ensureChannelReady(Merchant merchant, String channelCode) {
        ensureChannelReady(merchant, channelCode, "SANDBOX");
    }

    public void ensureChannelReady(Merchant merchant, String channelCode, String environment) {
        if (merchant == null) throw new PaymentGatewayException("Merchant is required");
        String normalizedEnvironment = normalizedEnvironment(environment);
        String allowedStatuses =
                "PRODUCTION".equals(normalizedEnvironment)
                        ? "('ACTIVE')"
                        : "('SANDBOX_TESTED','SUBMITTED_FOR_APPROVAL','ACTIVE')";
        String sql =
                "SELECT COUNT(*) FROM merchant_channel_credentials "
                        + "WHERE merchant_id=:merchant_id AND channel_code=:channel_code "
                        + "AND environment=:environment AND status IN "
                        + allowedStatuses
                        + " AND (channel_code NOT IN ('mtn_momo','airtel_open_api') OR (last_test_status='CONNECTIVITY_VERIFIED' AND tested_revision=revision))";
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("merchant_id", merchant.getId());
        p.addValue("channel_code", channelCode);
        p.addValue("environment", normalizedEnvironment);
        Integer count = jdbcTemplate.queryForObject(sql, p, Integer.class);
        if (count == null || count < 1) {
            throw new PaymentGatewayException(
                    "Merchant has not configured and approved channel "
                            + channelCode
                            + " for "
                            + normalizedEnvironment);
        }
    }

    public Map<String, Object> loadDecrypted(
            Merchant merchant, String channelCode, String environment) {
        if (merchant == null) throw new PaymentGatewayException("Merchant is required");
        return loadDecrypted(merchant.getId(), channelCode, environment);
    }

    private Map<String, Object> loadDecrypted(
            Long merchantId, String channelCode, String environment) {
        String sql =
                "SELECT credential_payload FROM merchant_channel_credentials WHERE"
                        + " merchant_id=:merchant_id AND channel_code=:channel_code AND"
                        + " environment=:environment LIMIT 1";
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("merchant_id", merchantId);
        p.addValue("channel_code", channelCode);
        p.addValue("environment", normalizedEnvironment(environment));
        String encrypted = jdbcTemplate.queryForObject(sql, p, String.class);
        return parseJson(cryptoService.decrypt(encrypted));
    }

    private Map<String, Object> find(Long merchantId, String channelCode, String environment) {
        String sql =
                "SELECT revision, tested_revision, decision_reason, channel_code, environment, display_name, credential_mask, status,"
                        + " last_test_status, last_test_message, last_tested_at,"
                        + " submitted_for_approval_at, approved_by, approved_at FROM"
                        + " merchant_channel_credentials WHERE merchant_id=:merchant_id AND"
                        + " channel_code=:channel_code AND environment=:environment LIMIT 1";
        List<Map<String, Object>> rows =
                jdbcTemplate.query(
                        sql,
                        params(merchantId, channelCode, normalizedEnvironment(environment)),
                        (rs, i) -> {
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("revision", rs.getLong("revision"));
                            r.put("testedRevision", rs.getObject("tested_revision"));
                            r.put("decisionReason", rs.getString("decision_reason"));
                            r.put("channelCode", rs.getString("channel_code"));
                            r.put("environment", rs.getString("environment"));
                            r.put("displayName", rs.getString("display_name"));
                            r.put("credentials", parseJson(rs.getString("credential_mask")));
                            r.put("status", rs.getString("status"));
                            r.put("lastTestStatus", rs.getString("last_test_status"));
                            r.put("lastTestMessage", rs.getString("last_test_message"));
                            r.put("lastTestedAt", rs.getString("last_tested_at"));
                            r.put(
                                    "submittedForApprovalAt",
                                    rs.getString("submitted_for_approval_at"));
                            r.put("approvedBy", rs.getString("approved_by"));
                            r.put("approvedAt", rs.getString("approved_at"));
                            return r;
                        });
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void validateRequiredCredentials(
            PaymentChannelAdapter adapter, Map<String, Object> credentials, String environment) {
        String channelCode = adapter.channelCode();
        if (MtnMomoCredentialSchema.CHANNEL_CODE.equalsIgnoreCase(channelCode)) {
            MtnMomoCredentialSchema.validate(
                    credentials,
                    environment,
                    adapter.countryCode(),
                    text(credentials.get("baseCurrency")));
            return;
        }
        if (AirtelOpenApiCredentialSchema.CHANNEL_CODE.equalsIgnoreCase(channelCode)) {
            AirtelOpenApiCredentialSchema.validate(
                    credentials,
                    environment,
                    text(credentials.get("country")),
                    text(credentials.get("currency")));
            return;
        }
        List<String> required = new ArrayList<>();
        if ("PRODUCTION".equals(normalizedEnvironment(environment))) {
            required.add("collectUrl");
            required.add("payoutUrl");
        }
        if ("safaricom_mpesa".equalsIgnoreCase(channelCode)) {
            required.add("shortCode");
            required.add("consumerKey");
            required.add("consumerSecret");
            required.add("passKey");
        } else {
            required.add("apiUser");
            required.add("apiKey");
            required.add("collectionAccount");
        }
        for (String key : required)
            if (!credentials.containsKey(key) || text(credentials.get(key)).isEmpty())
                throw new PaymentGatewayException("Missing required credential field: " + key);
    }

    private Map<String, Object> mask(Map<String, Object> values) {
        return CredentialEdits.mask(values);
    }

    public List<Map<String, Object>> approvalQueue() {
        return jdbcTemplate.queryForList(
                "SELECT c.id,c.merchant_id AS merchantId,m.account_number AS merchantNumber,c.channel_code AS channelCode,c.environment,c.revision,c.status,c.last_test_status AS lastTestStatus,c.last_tested_at AS lastTestedAt,c.updated_by AS requestedBy,c.decision_reason AS decisionReason FROM merchant_channel_credentials c JOIN merchants m ON m.id=c.merchant_id ORDER BY c.updated_at DESC LIMIT 500",
                Map.of());
    }

    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> decide(
            long id, long revision, String decision, String actor, String reason) {
        if (actor == null || actor.isBlank())
            throw new PaymentGatewayException("Authenticated checker is required");
        if (!List.of("ACTIVE", "REJECTED", "DISABLED").contains(decision))
            throw new PaymentGatewayException("Invalid credential decision");
        if (reason == null || reason.isBlank() || reason.length() > 1000)
            throw new PaymentGatewayException(
                    "Decision reason is required and must be at most 1000 characters");
        MapSqlParameterSource p = new MapSqlParameterSource("id", id);
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT * FROM merchant_channel_credentials WHERE id=:id FOR UPDATE", p);
        if (rows.size() != 1) throw new PaymentGatewayException("Credential request was not found");
        Map<String, Object> row = rows.get(0);
        if (((Number) row.get("revision")).longValue() != revision)
            throw new PaymentGatewayException(
                    "Credential revision changed; reload the approval queue");
        if (!"DISABLED".equals(decision)) {
            if (!"SUBMITTED_FOR_APPROVAL".equals(row.get("status")))
                throw new PaymentGatewayException("Only submitted credentials can be reviewed");
            if (actor.equalsIgnoreCase(text(row.get("updated_by"))))
                throw new PaymentGatewayException(
                        "Requester cannot approve or reject their own credentials");
        }
        if ("ACTIVE".equals(decision)
                && net.citotech.cito.gateway.MobileMoneyExecutionService.managed(
                        text(row.get("channel_code")))
                && (!"CONNECTIVITY_VERIFIED".equals(row.get("last_test_status"))
                        || !java.util.Objects.equals(
                                row.get("revision"), row.get("tested_revision"))))
            throw new PaymentGatewayException(
                    "Current credential revision requires provider connectivity verification");
        p.addValue("status", decision).addValue("actor", actor).addValue("reason", reason);
        jdbcTemplate.update(
                "UPDATE merchant_channel_credentials SET status=:status,approved_by=:actor,approved_at=CURRENT_TIMESTAMP,decision_reason=:reason WHERE id=:id",
                p);
        Long merchant = ((Number) row.get("merchant_id")).longValue();
        audit(
                merchant,
                text(row.get("channel_code")),
                text(row.get("environment")),
                decision,
                actor,
                reason);
        return find(merchant, text(row.get("channel_code")), text(row.get("environment")));
    }

    private void audit(
            Long merchantId,
            String channelCode,
            String environment,
            String action,
            String actor,
            String message) {
        MapSqlParameterSource p = params(merchantId, channelCode, environment);
        p.addValue("action", action);
        p.addValue("actor", actor);
        p.addValue("message", message);
        jdbcTemplate.update(
                "INSERT INTO merchant_channel_audit_events (merchant_id, channel_code, environment,"
                        + " action, actor, message) VALUES (:merchant_id, :channel_code, :environment,"
                        + " :action, :actor, :message)",
                p);
    }

    private MapSqlParameterSource params(Long merchantId, String channelCode, String environment) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("merchant_id", merchantId);
        p.addValue("channel_code", channelCode);
        p.addValue("environment", normalizedEnvironment(environment));
        return p;
    }

    private void requireUser(MerchantUser user) {
        if (user == null || user.getMerchant_id() == null)
            throw new PaymentGatewayException("Merchant session is required");
    }

    /**
     * Audit N7: channel credentials/API keys/webhook config are a DEVELOPER-level capability.
     * FINANCE and VIEWER team members can view channel status (see {@link #list}) but must not be
     * able to save, test, or submit-for-approval new channel credentials - that's config-changing,
     * merchant-facing self-service mutation. A missing/unrecognized role fails open to OWNER (see
     * {@link MerchantRole#fromString(String)}), so this only ever blocks a role that was
     * explicitly, deliberately set to something less privileged - it never locks out a merchant
     * user due to a null/unknown role value.
     */
    private void requireCanManageChannels(MerchantUser user) {
        if (!MerchantRole.fromString(user.getRole()).canManageChannels()) {
            throw new PaymentGatewayException(
                    "Your merchant role does not allow managing channel configuration");
        }
    }

    private String normalizedEnvironment(String environment) {
        return environmentService.normalizedEnvironment(environment);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map) return (Map<String, Object>) value;
        throw new PaymentGatewayException("credentials object is required");
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new PaymentGatewayException("Invalid credential payload");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
