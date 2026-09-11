package net.citotech.cito.sharedprovider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.gateway.AirtelOpenApiCredentialSchema;
import net.citotech.cito.gateway.MtnMomoCredentialSchema;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.merchant.MerchantChannelCredentialService;
import net.citotech.cito.merchant.MerchantChannelCryptoService;
import net.citotech.cito.merchant.MerchantEnvironmentService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-side credential-source decision for payment channels.
 *
 * <p>Merchant-owned approved credentials always win. CPay/platform credentials are used only when
 * an ACTIVE entitlement exists for the exact
 * merchant/channel/environment/country/currency/operation scope. Secrets are decrypted only for
 * execution and are never returned by admin read methods.
 */
@Service
public class SharedProviderAccessService {
    public static final String MERCHANT = "MERCHANT";
    public static final String PLATFORM_SHARED = "PLATFORM_SHARED";

    private final NamedParameterJdbcTemplate jdbc;
    private final MerchantChannelCredentialService merchantCredentials;
    private final MerchantChannelCryptoService crypto;
    private final MerchantEnvironmentService environments;
    private final ObjectMapper objectMapper;

    public SharedProviderAccessService(
            NamedParameterJdbcTemplate jdbc,
            MerchantChannelCredentialService merchantCredentials,
            MerchantChannelCryptoService crypto,
            MerchantEnvironmentService environments,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.merchantCredentials = merchantCredentials;
        this.crypto = crypto;
        this.environments = environments;
        this.objectMapper = objectMapper;
    }

    public boolean isReady(
            Merchant merchant,
            String channelCode,
            String environment,
            String country,
            String currency,
            String operation,
            BigDecimal amount) {
        return isReady(
                merchant, channelCode, environment, country, currency, operation, amount, null);
    }

    public boolean isReady(
            Merchant merchant,
            String channelCode,
            String environment,
            String country,
            String currency,
            String operation,
            BigDecimal amount,
            String preferredSource) {
        String source = normalizeSource(preferredSource);
        if (PLATFORM_SHARED.equals(source)) {
            return findActiveEntitlement(
                                    merchant,
                                    channelCode,
                                    environment,
                                    country,
                                    currency,
                                    operation,
                                    amount)
                            != null
                    && hasActivePlatformCredential(channelCode, environment, country, currency)
                    && sharedFundsReady(
                            merchant,
                            channelCode,
                            environment,
                            country,
                            currency,
                            operation,
                            amount);
        }
        try {
            merchantCredentials.ensureChannelReady(merchant, channelCode, environment);
            return true;
        } catch (PaymentGatewayException ignored) {
            if (MERCHANT.equals(source)) return false;
            return findActiveEntitlement(
                                    merchant,
                                    channelCode,
                                    environment,
                                    country,
                                    currency,
                                    operation,
                                    amount)
                            != null
                    && hasActivePlatformCredential(channelCode, environment, country, currency)
                    && sharedFundsReady(
                            merchant,
                            channelCode,
                            environment,
                            country,
                            currency,
                            operation,
                            amount);
        }
    }

    private boolean sharedFundsReady(
            Merchant merchant,
            String channel,
            String environment,
            String country,
            String currency,
            String operation,
            BigDecimal amount) {
        MapSqlParameterSource p =
                scope(merchant.getId(), channel, environment, country, currency, operation);
        BigDecimal requested = amount == null ? BigDecimal.ZERO : amount;
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT e.daily_limit,COALESCE(u.approved_amount,0) AS used FROM shared_provider_entitlements e LEFT JOIN shared_provider_daily_usage u ON u.entitlement_id=e.id AND u.usage_date=CURRENT_DATE AND u.operation='AUTHORIZED' WHERE e.merchant_id=:merchant_id AND e.channel_code=:channel AND e.environment=:environment AND e.country_code=:country AND e.currency_code=:currency AND e.operation=:operation AND e.status='ACTIVE'",
                        p);
        if (rows.size() != 1) return false;
        BigDecimal limit = decimalOrNull(rows.get(0).get("daily_limit"));
        if (limit != null
                && limit.subtract(decimal(rows.get(0).get("used"))).compareTo(requested) < 0)
            return false;
        if (!"PAYOUT".equalsIgnoreCase(operation)) return true;
        List<BigDecimal> available =
                jdbc.query(
                        "SELECT book_balance-reserved_balance-pending_outgoing_balance FROM provider_treasury_accounts WHERE channel_code=:channel AND environment=:environment AND country_code=:country AND currency_code=:currency AND account_role='DISBURSEMENT'",
                        p,
                        (rs, n) -> rs.getBigDecimal(1));
        return available.size() == 1 && available.get(0).compareTo(requested) >= 0;
    }

    /** Resolve the actual credential source. Call once, after routing has selected the adapter. */
    @Transactional
    public CredentialContext resolve(
            Merchant merchant,
            String channelCode,
            String environment,
            String country,
            String currency,
            String operation,
            BigDecimal amount) {
        return resolve(
                merchant, channelCode, environment, country, currency, operation, amount, null);
    }

    @Transactional
    public CredentialContext resolve(
            Merchant merchant,
            String channelCode,
            String environment,
            String country,
            String currency,
            String operation,
            BigDecimal amount,
            String preferredSource) {
        requireMerchant(merchant);
        String env = environments.normalizedEnvironment(environment);
        String source = normalizeSource(preferredSource);
        if (!PLATFORM_SHARED.equals(source)) {
            try {
                merchantCredentials.ensureChannelReady(merchant, channelCode, env);
                return new CredentialContext(
                        MERCHANT,
                        merchantCredentials.loadDecrypted(merchant, channelCode, env),
                        null,
                        normalizeCountry(country),
                        normalizeCurrency(currency),
                        normalizeOperation(operation));
            } catch (PaymentGatewayException ignored) {
                if (MERCHANT.equals(source)) {
                    throw new PaymentGatewayException(
                            "Approved merchant-owned credentials are required for " + channelCode);
                }
                // Default behavior falls back to the explicitly entitled shared connection.
            }
        }

        Map<String, Object> entitlement =
                findActiveEntitlement(
                        merchant, channelCode, env, country, currency, operation, amount);
        if (entitlement == null) {
            throw new PaymentGatewayException(
                    "Merchant has neither approved channel credentials nor an active CPay shared-provider entitlement for "
                            + channelCode);
        }
        Long entitlementId = number(entitlement.get("id"));
        consumeDailyLimit(entitlement, amount);
        Map<String, Object> credentials =
                loadPlatformCredential(channelCode, env, country, currency);
        return new CredentialContext(
                PLATFORM_SHARED,
                credentials,
                entitlementId,
                normalizeCountry(country),
                normalizeCurrency(currency),
                normalizeOperation(operation));
    }

    public List<Map<String, Object>> listEntitlements() {
        return jdbc.queryForList(
                "SELECT e.id, e.merchant_id AS merchantId, m.name AS merchantName,"
                        + " m.account_number AS merchantNumber, e.channel_code AS channelCode,"
                        + " e.environment, e.country_code AS countryCode,"
                        + " e.currency_code AS currencyCode, e.operation, e.status,"
                        + " e.per_transaction_limit AS perTransactionLimit, e.daily_limit AS dailyLimit,"
                        + " COALESCE(u.approved_amount,0) AS usedToday,"
                        + " e.requested_by AS requestedBy, e.requested_at AS requestedAt,"
                        + " e.approved_by AS approvedBy, e.approved_at AS approvedAt, e.notes"
                        + " FROM shared_provider_entitlements e JOIN merchants m ON m.id=e.merchant_id"
                        + " LEFT JOIN shared_provider_daily_usage u ON u.entitlement_id=e.id"
                        + " AND u.usage_date=CURRENT_DATE AND u.operation='AUTHORIZED'"
                        + " ORDER BY e.updated_at DESC",
                Map.of());
    }

    @Transactional
    public Map<String, Object> requestEntitlement(Map<String, Object> body, String actor) {
        String operation = normalizeOperation(text(body.get("operation")));
        BigDecimal perTx = decimalOrNull(body.get("perTransactionLimit"));
        BigDecimal daily = decimalOrNull(body.get("dailyLimit"));
        if (perTx != null && perTx.signum() <= 0)
            throw new PaymentGatewayException("perTransactionLimit must be positive");
        if (daily != null && daily.signum() <= 0)
            throw new PaymentGatewayException("dailyLimit must be positive");
        if (perTx != null && daily != null && perTx.compareTo(daily) > 0) {
            throw new PaymentGatewayException("perTransactionLimit cannot exceed dailyLimit");
        }
        MapSqlParameterSource p =
                new MapSqlParameterSource()
                        .addValue("merchant_id", requiredLong(body.get("merchantId"), "merchantId"))
                        .addValue(
                                "channel_code",
                                requiredText(body.get("channelCode"), "channelCode"))
                        .addValue(
                                "environment",
                                environments.normalizedEnvironment(
                                        requiredText(body.get("environment"), "environment")))
                        .addValue(
                                "country",
                                normalizeCountry(
                                        requiredText(body.get("countryCode"), "countryCode")))
                        .addValue(
                                "currency",
                                normalizeCurrency(
                                        requiredText(body.get("currencyCode"), "currencyCode")))
                        .addValue("operation", operation)
                        .addValue("per_tx", perTx)
                        .addValue("daily", daily)
                        .addValue("actor", requiredActor(actor))
                        .addValue("notes", text(body.get("notes")));
        jdbc.update(
                "INSERT INTO shared_provider_entitlements (merchant_id, channel_code, environment, country_code, currency_code, operation, status, per_transaction_limit, daily_limit, requested_by, notes) "
                        + "VALUES (:merchant_id,:channel_code,:environment,:country,:currency,:operation,'PENDING',:per_tx,:daily,:actor,:notes) "
                        + "ON DUPLICATE KEY UPDATE status='PENDING', per_transaction_limit=:per_tx, daily_limit=:daily, requested_by=:actor, requested_at=CURRENT_TIMESTAMP(6), approved_by=NULL, approved_at=NULL, rejected_by=NULL, rejected_at=NULL, disabled_by=NULL, disabled_at=NULL, notes=:notes",
                p);
        return oneEntitlement(p);
    }

    @Transactional
    public Map<String, Object> approveEntitlement(long id, String actor) {
        String approver = requiredActor(actor);
        Map<String, Object> row = lockEntitlement(id);
        if (!"PENDING".equals(text(row.get("status"))))
            throw new PaymentGatewayException("Only PENDING entitlements can be approved");
        if (approver.equalsIgnoreCase(text(row.get("requested_by")))) {
            throw new PaymentGatewayException(
                    "Maker-checker violation: requester cannot approve the same entitlement");
        }
        jdbc.update(
                "UPDATE shared_provider_entitlements SET status='ACTIVE', approved_by=:actor, approved_at=CURRENT_TIMESTAMP(6) WHERE id=:id",
                new MapSqlParameterSource().addValue("id", id).addValue("actor", approver));
        if ("PAYOUT".equals(text(row.get("operation")))) {
            grantMerchantApi(number(row.get("merchant_id")), "MOBILE_MONEY_PAYOUT");
        }
        return entitlementById(id);
    }

    @Transactional
    public Map<String, Object> rejectEntitlement(long id, String actor) {
        String checker = requiredActor(actor);
        Map<String, Object> row = lockEntitlement(id);
        if (!"PENDING".equals(text(row.get("status"))))
            throw new PaymentGatewayException("Only PENDING entitlements can be rejected");
        if (checker.equalsIgnoreCase(text(row.get("requested_by")))) {
            throw new PaymentGatewayException(
                    "Maker-checker violation: requester cannot reject the same entitlement");
        }
        jdbc.update(
                "UPDATE shared_provider_entitlements SET status='REJECTED', rejected_by=:actor, rejected_at=CURRENT_TIMESTAMP(6) WHERE id=:id",
                new MapSqlParameterSource().addValue("id", id).addValue("actor", checker));
        return entitlementById(id);
    }

    @Transactional
    public Map<String, Object> disableEntitlement(long id, String actor) {
        jdbc.update(
                "UPDATE shared_provider_entitlements SET status='DISABLED', disabled_by=:actor, disabled_at=CURRENT_TIMESTAMP(6) WHERE id=:id",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("actor", requiredActor(actor)));
        return entitlementById(id);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private net.citotech.cito.gateway.ProviderCredentialProbeService connectivity;

    public Map<String, Object> verifyPlatformCredential(long id, String actor) {
        requiredActor(actor);
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT * FROM platform_channel_credentials WHERE id=:id",
                        new MapSqlParameterSource("id", id));
        try {
            connectivity.verify(
                    text(row.get("channel_code")),
                    text(row.get("environment")),
                    text(row.get("country_code")),
                    text(row.get("currency_code")),
                    parseJson(crypto.decrypt(text(row.get("credential_payload")))));
        } catch (RuntimeException failure) {
            jdbc.update(
                    "UPDATE platform_channel_credentials SET last_test_status='CONNECTIVITY_FAILED',tested_revision=revision,last_tested_at=CURRENT_TIMESTAMP WHERE id=:id AND revision=:revision",
                    new MapSqlParameterSource("id", id).addValue("revision", row.get("revision")));
            throw new PaymentGatewayException(
                    "Provider authentication could not be verified; check credentials and provider availability");
        }
        int updated =
                jdbc.update(
                        "UPDATE platform_channel_credentials SET last_test_status='CONNECTIVITY_VERIFIED',tested_revision=revision,last_tested_at=CURRENT_TIMESTAMP WHERE id=:id AND revision=:revision",
                        new MapSqlParameterSource("id", id)
                                .addValue("revision", row.get("revision")));
        if (updated != 1)
            throw new PaymentGatewayException(
                    "Credentials changed during verification; verify the new revision");
        return platformCredentialById(id);
    }

    public List<Map<String, Object>> listPlatformCredentials() {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT id, revision, last_test_status AS lastTestStatus, channel_code AS channelCode, environment, country_code AS countryCode, currency_code AS currencyCode, credential_mask AS credentialMask, status, created_by AS createdBy, updated_by AS updatedBy, approved_by AS approvedBy, approved_at AS approvedAt, updated_at AS updatedAt FROM platform_channel_credentials ORDER BY channel_code, environment, country_code, currency_code",
                        Map.of());
        for (Map<String, Object> row : rows) {
            row.put("credentials", parseJson(text(row.remove("credentialMask"))));
        }
        return rows;
    }

    @Transactional
    public Map<String, Object> savePlatformCredential(Map<String, Object> body, String actor) {
        Map<String, Object> credentials = map(body.get("credentials"));
        String channel = requiredText(body.get("channelCode"), "channelCode");
        String environment =
                environments.normalizedEnvironment(
                        requiredText(body.get("environment"), "environment"));
        String country = normalizeCountry(requiredText(body.get("countryCode"), "countryCode"));
        String currency = normalizeCurrency(requiredText(body.get("currencyCode"), "currencyCode"));
        ensureTreasuryAccounts(channel, environment, country, currency);
        List<Map<String, Object>> existing =
                jdbc.queryForList(
                        "SELECT * FROM platform_channel_credentials WHERE channel_code=:channel AND environment=:env AND country_code=:country AND currency_code=:currency FOR UPDATE",
                        new MapSqlParameterSource("channel", channel)
                                .addValue("env", environment)
                                .addValue("country", country)
                                .addValue("currency", currency));
        if (!existing.isEmpty()) {
            Map<String, Object> previous = existing.get(0);
            if (!(body.get("revision") instanceof Number)
                    || ((Number) body.get("revision")).longValue()
                            != ((Number) previous.get("revision")).longValue())
                throw new PaymentGatewayException(
                        "Platform credentials changed; reload before saving");
            credentials =
                    net.citotech.cito.merchant.CredentialEdits.merge(
                            parseJson(crypto.decrypt(text(previous.get("credential_payload")))),
                            credentials,
                            body.get("clearFields") instanceof List<?> clear ? clear : List.of());
        }

        if (MtnMomoCredentialSchema.CHANNEL_CODE.equalsIgnoreCase(channel)) {
            MtnMomoCredentialSchema.validate(credentials, environment, country, currency);
        } else if (AirtelOpenApiCredentialSchema.CHANNEL_CODE.equalsIgnoreCase(channel)) {
            AirtelOpenApiCredentialSchema.validate(credentials, environment, country, currency);
        } else if ("PRODUCTION".equals(environment)) {
            requiredText(credentials.get("collectUrl"), "credentials.collectUrl");
            requiredText(credentials.get("payoutUrl"), "credentials.payoutUrl");
        }
        String who = requiredActor(actor);
        MapSqlParameterSource p =
                new MapSqlParameterSource()
                        .addValue("channel", channel)
                        .addValue("environment", environment)
                        .addValue("country", country)
                        .addValue("currency", currency)
                        .addValue("payload", crypto.encrypt(json(credentials)))
                        .addValue("mask", json(mask(credentials)))
                        .addValue("actor", who);
        jdbc.update(
                "INSERT INTO platform_channel_credentials (channel_code, environment, country_code, currency_code, credential_payload, credential_mask, status, created_by, updated_by) "
                        + "VALUES (:channel,:environment,:country,:currency,:payload,:mask,'CONFIGURED',:actor,:actor) "
                        + "ON DUPLICATE KEY UPDATE credential_payload=:payload, credential_mask=:mask, revision=revision+1, last_test_status=NULL, tested_revision=NULL, last_tested_at=NULL, status='CONFIGURED', updated_by=:actor, approved_by=NULL, approved_at=NULL, disabled_by=NULL, disabled_at=NULL",
                p);
        return platformCredential(channel, environment, country, currency);
    }

    private void ensureTreasuryAccounts(
            String channel, String environment, String country, String currency) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM provider_treasury_accounts "
                                + "WHERE channel_code=:channel AND environment=:environment "
                                + "AND country_code=:country AND currency_code=:currency "
                                + "AND account_role IN ('COLLECTION','DISBURSEMENT')",
                        new MapSqlParameterSource()
                                .addValue("channel", channel)
                                .addValue("environment", environment)
                                .addValue("country", country)
                                .addValue("currency", currency),
                        Integer.class);
        if (count == null || count != 2) {
            throw new PaymentGatewayException(
                    "Collection and Disbursement treasury sub-accounts must exist before saving platform credentials");
        }
    }

    @Transactional
    public Map<String, Object> approvePlatformCredential(long id, String actor) {
        String approver = requiredActor(actor);
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT id, channel_code, status, updated_by, revision, tested_revision, last_test_status FROM platform_channel_credentials WHERE id=:id FOR UPDATE",
                        new MapSqlParameterSource().addValue("id", id));
        if (rows.isEmpty()) throw new PaymentGatewayException("Platform credential not found");
        Map<String, Object> row = rows.get(0);
        if (net.citotech.cito.gateway.MobileMoneyExecutionService.managed(
                        text(row.get("channel_code")))
                && (!"CONNECTIVITY_VERIFIED".equals(row.get("last_test_status"))
                        || !java.util.Objects.equals(
                                row.get("revision"), row.get("tested_revision"))))
            throw new PaymentGatewayException(
                    "Verify provider connectivity for the current credential revision before approval");
        if (!"CONFIGURED".equals(text(row.get("status"))))
            throw new PaymentGatewayException(
                    "Only CONFIGURED platform credentials can be approved");
        if (approver.equalsIgnoreCase(text(row.get("updated_by")))) {
            throw new PaymentGatewayException(
                    "Maker-checker violation: credential editor cannot approve the same credential");
        }
        jdbc.update(
                "UPDATE platform_channel_credentials SET status='ACTIVE', approved_by=:actor, approved_at=CURRENT_TIMESTAMP(6) WHERE id=:id",
                new MapSqlParameterSource().addValue("id", id).addValue("actor", approver));
        return platformCredentialById(id);
    }

    @Transactional
    public Map<String, Object> disablePlatformCredential(long id, String actor) {
        jdbc.update(
                "UPDATE platform_channel_credentials SET status='DISABLED', disabled_by=:actor, disabled_at=CURRENT_TIMESTAMP(6) WHERE id=:id",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("actor", requiredActor(actor)));
        return platformCredentialById(id);
    }

    private Map<String, Object> findActiveEntitlement(
            Merchant merchant,
            String channel,
            String environment,
            String country,
            String currency,
            String operation,
            BigDecimal amount) {
        requireMerchant(merchant);
        MapSqlParameterSource p =
                scope(merchant.getId(), channel, environment, country, currency, operation);
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT id, status, per_transaction_limit, daily_limit, requested_by FROM shared_provider_entitlements "
                                + "WHERE merchant_id=:merchant_id AND channel_code=:channel AND environment=:environment AND country_code=:country AND currency_code=:currency AND operation=:operation AND status='ACTIVE' LIMIT 1",
                        p);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        BigDecimal perTx = decimalOrNull(row.get("per_transaction_limit"));
        if (perTx != null && amount != null && amount.compareTo(perTx) > 0) return null;
        return row;
    }

    private boolean hasActivePlatformCredential(
            String channel, String environment, String country, String currency) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM platform_channel_credentials WHERE channel_code=:channel AND environment=:environment AND country_code=:country AND currency_code=:currency AND status='ACTIVE' AND (channel_code NOT IN ('mtn_momo','airtel_open_api') OR (last_test_status='CONNECTIVITY_VERIFIED' AND tested_revision=revision))",
                        new MapSqlParameterSource()
                                .addValue("channel", channel)
                                .addValue(
                                        "environment",
                                        environments.normalizedEnvironment(environment))
                                .addValue("country", normalizeCountry(country))
                                .addValue("currency", normalizeCurrency(currency)),
                        Integer.class);
        return count != null && count > 0;
    }

    private Map<String, Object> loadPlatformCredential(
            String channel, String environment, String country, String currency) {
        List<String> rows =
                jdbc.query(
                        "SELECT credential_payload FROM platform_channel_credentials WHERE channel_code=:channel AND environment=:environment AND country_code=:country AND currency_code=:currency AND status='ACTIVE' AND (channel_code NOT IN ('mtn_momo','airtel_open_api') OR (last_test_status='CONNECTIVITY_VERIFIED' AND tested_revision=revision)) LIMIT 1",
                        new MapSqlParameterSource()
                                .addValue("channel", channel)
                                .addValue(
                                        "environment",
                                        environments.normalizedEnvironment(environment))
                                .addValue("country", normalizeCountry(country))
                                .addValue("currency", normalizeCurrency(currency)),
                        (rs, i) -> rs.getString(1));
        if (rows.isEmpty())
            throw new PaymentGatewayException(
                    "No approved CPay platform credential is configured for " + channel);
        return parseJson(crypto.decrypt(rows.get(0)));
    }

    /** Decrypt an approved platform credential only for a server-side provider operation. */
    public Map<String, Object> loadActivePlatformCredential(
            String channel, String environment, String country, String currency) {
        return loadPlatformCredential(channel, environment, country, currency);
    }

    private void consumeDailyLimit(Map<String, Object> entitlement, BigDecimal amount) {
        BigDecimal dailyLimit = decimalOrNull(entitlement.get("daily_limit"));
        if (dailyLimit == null || amount == null) return;
        Long entitlementId = number(entitlement.get("id"));
        MapSqlParameterSource p =
                new MapSqlParameterSource()
                        .addValue("id", entitlementId)
                        .addValue("day", LocalDate.now())
                        .addValue("operation", "AUTHORIZED")
                        .addValue("amount", amount);
        jdbc.update(
                "INSERT IGNORE INTO shared_provider_daily_usage (entitlement_id, usage_date, operation, approved_amount, transaction_count) VALUES (:id,:day,:operation,0,0)",
                p);
        List<Map<String, Object>> usage =
                jdbc.queryForList(
                        "SELECT id, approved_amount FROM shared_provider_daily_usage WHERE entitlement_id=:id AND usage_date=:day AND operation=:operation FOR UPDATE",
                        p);
        BigDecimal used =
                usage.isEmpty() ? BigDecimal.ZERO : decimal(usage.get(0).get("approved_amount"));
        BigDecimal proposed = used.add(amount);
        if (proposed.compareTo(dailyLimit) > 0) {
            throw new PaymentGatewayException("CPay shared-provider daily limit exceeded");
        }
        p.addValue("proposed", proposed);
        jdbc.update(
                "UPDATE shared_provider_daily_usage SET approved_amount=:proposed, transaction_count=transaction_count+1 WHERE entitlement_id=:id AND usage_date=:day AND operation=:operation",
                p);
    }

    private MapSqlParameterSource scope(
            Long merchantId,
            String channel,
            String environment,
            String country,
            String currency,
            String operation) {
        return new MapSqlParameterSource()
                .addValue("merchant_id", merchantId)
                .addValue("channel", channel)
                .addValue("environment", environments.normalizedEnvironment(environment))
                .addValue("country", normalizeCountry(country))
                .addValue("currency", normalizeCurrency(currency))
                .addValue("operation", normalizeOperation(operation));
    }

    private Map<String, Object> oneEntitlement(MapSqlParameterSource p) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT id, merchant_id AS merchantId, channel_code AS channelCode, environment, country_code AS countryCode, currency_code AS currencyCode, operation, status, per_transaction_limit AS perTransactionLimit, daily_limit AS dailyLimit, requested_by AS requestedBy, requested_at AS requestedAt, approved_by AS approvedBy, approved_at AS approvedAt, notes FROM shared_provider_entitlements WHERE merchant_id=:merchant_id AND channel_code=:channel_code AND environment=:environment AND country_code=:country AND currency_code=:currency AND operation=:operation LIMIT 1",
                        p);
        if (rows.isEmpty()) throw new PaymentGatewayException("Entitlement was not saved");
        return rows.get(0);
    }

    private Map<String, Object> lockEntitlement(long id) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT * FROM shared_provider_entitlements WHERE id=:id FOR UPDATE",
                        new MapSqlParameterSource().addValue("id", id));
        if (rows.isEmpty())
            throw new PaymentGatewayException("Shared-provider entitlement not found");
        return rows.get(0);
    }

    private Map<String, Object> entitlementById(long id) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT id, merchant_id AS merchantId, channel_code AS channelCode, environment, country_code AS countryCode, currency_code AS currencyCode, operation, status, per_transaction_limit AS perTransactionLimit, daily_limit AS dailyLimit, requested_by AS requestedBy, requested_at AS requestedAt, approved_by AS approvedBy, approved_at AS approvedAt, notes FROM shared_provider_entitlements WHERE id=:id",
                        new MapSqlParameterSource().addValue("id", id));
        if (rows.isEmpty())
            throw new PaymentGatewayException("Shared-provider entitlement not found");
        return rows.get(0);
    }

    private Map<String, Object> platformCredential(
            String channel, String environment, String country, String currency) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT id, revision, last_test_status AS lastTestStatus, channel_code AS channelCode, environment, country_code AS countryCode, currency_code AS currencyCode, credential_mask AS credentialMask, status, created_by AS createdBy, updated_by AS updatedBy, approved_by AS approvedBy, approved_at AS approvedAt FROM platform_channel_credentials WHERE channel_code=:channel AND environment=:environment AND country_code=:country AND currency_code=:currency LIMIT 1",
                        new MapSqlParameterSource()
                                .addValue("channel", channel)
                                .addValue("environment", environment)
                                .addValue("country", country)
                                .addValue("currency", currency));
        if (rows.isEmpty()) throw new PaymentGatewayException("Platform credential not found");
        return safeCredential(rows.get(0));
    }

    private Map<String, Object> platformCredentialById(long id) {
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT id, revision, last_test_status AS lastTestStatus, channel_code AS channelCode, environment, country_code AS countryCode, currency_code AS currencyCode, credential_mask AS credentialMask, status, created_by AS createdBy, updated_by AS updatedBy, approved_by AS approvedBy, approved_at AS approvedAt FROM platform_channel_credentials WHERE id=:id",
                        new MapSqlParameterSource().addValue("id", id));
        if (rows.isEmpty()) throw new PaymentGatewayException("Platform credential not found");
        return safeCredential(rows.get(0));
    }

    private Map<String, Object> safeCredential(Map<String, Object> row) {
        Map<String, Object> safe = new LinkedHashMap<>(row);
        safe.put("credentials", parseJson(text(safe.remove("credentialMask"))));
        return safe;
    }

    private Map<String, Object> mask(Map<String, Object> values) {
        return net.citotech.cito.merchant.CredentialEdits.mask(values);
    }

    private Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?>) return new LinkedHashMap<>((Map<String, Object>) value);
        throw new PaymentGatewayException("credentials object is required");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String value) {
        try {
            return objectMapper.readValue(value, Map.class);
        } catch (Exception e) {
            throw new PaymentGatewayException("Unable to read credential configuration");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new PaymentGatewayException("Unable to encode credential configuration");
        }
    }

    private void requireMerchant(Merchant merchant) {
        if (merchant == null || merchant.getId() == null)
            throw new PaymentGatewayException("Merchant is required");
    }

    private String normalizeCountry(String value) {
        return requiredText(value, "country").toUpperCase(Locale.ROOT);
    }

    private String normalizeCurrency(String value) {
        return requiredText(value, "currency").toUpperCase(Locale.ROOT);
    }

    private String normalizeOperation(String value) {
        String operation = requiredText(value, "operation").toUpperCase(Locale.ROOT);
        if (!operation.equals("COLLECT") && !operation.equals("PAYOUT"))
            throw new PaymentGatewayException("operation must be COLLECT or PAYOUT");
        return operation;
    }

    private String normalizeSource(String value) {
        String source = text(value).toUpperCase(Locale.ROOT);
        if (source.isEmpty()) return "";
        if (!MERCHANT.equals(source) && !PLATFORM_SHARED.equals(source)) {
            throw new PaymentGatewayException(
                    "credentialSource must be MERCHANT or PLATFORM_SHARED");
        }
        return source;
    }

    private void grantMerchantApi(long merchantId, String api) {
        jdbc.update(
                "UPDATE merchants SET allowed_apis=CASE"
                        + " WHEN allowed_apis IS NULL OR TRIM(allowed_apis)='' THEN :api"
                        + " ELSE CONCAT(TRIM(TRAILING ',' FROM allowed_apis),',',:api) END"
                        + " WHERE id=:merchant AND FIND_IN_SET(:api,REPLACE(COALESCE(allowed_apis,''),' ',''))=0",
                new MapSqlParameterSource().addValue("merchant", merchantId).addValue("api", api));
    }

    private String requiredActor(String actor) {
        return requiredText(actor, "actor");
    }

    private String requiredText(Object value, String field) {
        String v = text(value);
        if (v.isEmpty()) throw new PaymentGatewayException(field + " is required");
        return v;
    }

    private long requiredLong(Object value, String field) {
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(requiredText(value, field));
        } catch (NumberFormatException e) {
            throw new PaymentGatewayException(field + " must be a number");
        }
    }

    private Long number(Object value) {
        return value instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(value));
    }

    private BigDecimal decimal(Object value) {
        return value instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(value));
    }

    private BigDecimal decimalOrNull(Object value) {
        if (value == null || text(value).isEmpty()) return null;
        try {
            return decimal(value);
        } catch (Exception e) {
            throw new PaymentGatewayException("Invalid monetary limit");
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record CredentialContext(
            String source,
            Map<String, Object> credentials,
            Long entitlementId,
            String countryCode,
            String currencyCode,
            String operation) {
        public boolean shared() {
            return PLATFORM_SHARED.equals(source);
        }
    }
}
