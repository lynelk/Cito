package net.citotech.cito.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import net.citotech.cito.Common;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.Model.Setting;
import net.citotech.cito.merchant.MerchantChannelCredentialService;
import net.citotech.cito.sharedprovider.SharedProviderAccessService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** Pins ownership and provider account identity, while allowing secret rotation on that account. */
@Service
public class AirtelRecoveryCredentials {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectProvider<MerchantChannelCredentialService> merchantStore;
    private final ObjectProvider<SharedProviderAccessService> platformStore;

    public AirtelRecoveryCredentials(
            NamedParameterJdbcTemplate jdbc,
            ObjectProvider<MerchantChannelCredentialService> merchantStore,
            ObjectProvider<SharedProviderAccessService> platformStore) {
        this.jdbc = jdbc;
        this.merchantStore = merchantStore;
        this.platformStore = platformStore;
    }

    public Map<String, String> load(
            long merchantId, String source, String environment, String country, String currency) {
        if (source.startsWith("LEGACY_")) return legacy(merchantId, source, country, currency);
        Map<String, Object> raw;
        if ("PLATFORM_SHARED".equals(source)) {
            raw =
                    platformStore
                            .getObject()
                            .loadActivePlatformCredential(
                                    AirtelOpenApiAdapter.CHANNEL_CODE,
                                    environment,
                                    country,
                                    currency);
        } else if ("MERCHANT".equals(source)) {
            Integer active =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM merchant_channel_credentials"
                                    + " WHERE merchant_id=:merchant AND channel_code=:channel AND environment=:environment"
                                    + " AND (status='ACTIVE' OR (environment='SANDBOX' AND status='SANDBOX_TESTED'))",
                            Map.of(
                                    "merchant",
                                    merchantId,
                                    "channel",
                                    AirtelOpenApiAdapter.CHANNEL_CODE,
                                    "environment",
                                    environment),
                            Integer.class);
            if (active == null || active != 1)
                throw new PaymentGatewayException("AIRTEL_CREDENTIAL_NOT_ACTIVE");
            Merchant merchant = Common.getMerchantById(Long.toString(merchantId), jdbc);
            raw =
                    merchantStore
                            .getObject()
                            .loadDecrypted(
                                    merchant, AirtelOpenApiAdapter.CHANNEL_CODE, environment);
        } else throw new PaymentGatewayException("AIRTEL_CREDENTIAL_SOURCE_INVALID");
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach(
                (k, v) -> {
                    if (v != null) result.put(k, v.toString().trim());
                });
        return result;
    }

    Map<String, String> legacy(long merchantId, String source, String country, String currency) {
        boolean owned = "LEGACY_MERCHANT".equals(source);
        if (!owned && !"LEGACY_PLATFORM".equals(source))
            throw new PaymentGatewayException("AIRTEL_CREDENTIAL_SOURCE_INVALID");
        // Legacy balance finalisation still uses this global switch. Never silently switch
        // accounting modes.
        if (owned != Common.useMerchantProviderCredentials(jdbc))
            throw new PaymentGatewayException("AIRTEL_LEGACY_ACCOUNTING_SCOPE_CHANGED");
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> names =
                Map.ofEntries(
                        Map.entry("baseUrl", "gw_airtelmoney_api_url"),
                                Map.entry("clientId", "gw_airtelmoney_api_username"),
                        Map.entry("clientSecret", "gw_airtelmoney_api_password"),
                                Map.entry("apiPin", "gw_airtelmoney_api_pin"),
                        Map.entry("publicKey", "gw_airtelmoney_api_public_key"),
                                Map.entry("tokenPath", "gw_airtelmoney_token_url"),
                        Map.entry("collectionPath", "gw_airtelmoney_collections_url"),
                                Map.entry("payoutPath", "gw_airtelmoney_disbursements_url"),
                        Map.entry("collectionStatusPath", "gw_airtelmoney_collections_status_url"),
                                Map.entry(
                                        "payoutStatusPath",
                                        "gw_airtelmoney_disbursements_status_url"));
        names.forEach(
                (key, name) -> {
                    Setting setting =
                            owned
                                    ? Common.getMerchantSettings(name, merchantId, jdbc)
                                    : Common.getSettings(name, jdbc);
                    if (setting != null
                            && setting.getSetting_value() != null
                            && !setting.getSetting_value().isBlank())
                        values.put(key, setting.getSetting_value().trim());
                });
        values.put("country", country);
        values.put("currency", currency);
        return values;
    }

    static String identity(
            Map<String, String> values, String environment, String country, String currency) {
        return ProviderTokenScope.segment(
                "AIRTEL_ACCOUNT",
                values.get("baseUrl"),
                values.get("clientId"),
                environment,
                country,
                currency,
                values.get("tokenPath"),
                values.get("collectionStatusPath"),
                values.get("payoutStatusPath"));
    }
}
