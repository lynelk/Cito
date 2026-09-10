package net.citotech.cito.Model;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import net.citotech.cito.Common;
import net.citotech.cito.gateway.ProviderErrorTranslator;
import net.citotech.cito.gateway.ProviderToken;
import net.citotech.cito.gateway.ProviderTokenScope;
import net.citotech.cito.gateway.ProviderTokenStoreRegistry;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AirtelMoneyOpenApiPaymentGateway extends PaymentGateway {
    // Audit H1: converted from java.util.logging to SLF4J (money-path class: Airtel OpenAPI
    // gateway).
    private static final Logger logger =
            LoggerFactory.getLogger(AirtelMoneyOpenApiPaymentGateway.class);
    private static final int TOKEN_TTL_MINUTES = 1;

    String xml_sent = "";
    String xml_returned = "";
    String mode = "TEST";
    String global_url = "https://openapiuat.airtel.africa";
    String env = "mtnuganda";
    String base_currency = "UGX";
    String country = "UG";
    String segment = "collection";
    String api_pin = "";
    String tokenPath = "/auth/oauth2/token";
    String collectionsPath = "/merchant/v2/payments/";
    String disbursementsPath = "/standard/v2/disbursements/";
    String balancePath = "/standard/v2/users/balance";
    String collectionStatusPath = "/standard/v2/payments/{reference}/";
    String disbursementStatusPath = "/standard/v2/disbursements/{reference}/";

    public static String BALANCE_TYPE = "airtelmm_balance";
    public static String[] prefix = {"25675", "25670", "25676"};
    public static String gateway_id = "AirtelMoneyOpenApiPaymentGateway";
    public static String gateway_currency_code = "AIRTELMM";

    String api_collections_user = "";
    String api_collections_key = "";
    String api_collections_subscription = "";
    String api_disbursements_user = "";
    String api_disbursements_key = "";
    String api_disbursements_subscription = "";

    String publicKey = "";

    public static boolean isValidMisdn(String msisdn) {
        Boolean fromRoutingTable =
                net.citotech.cito.gateway.ChannelRoutingRegistry.matchesConfiguredPrefix(
                        gateway_id, msisdn);
        if (fromRoutingTable != null) {
            return fromRoutingTable;
        }
        for (String p : prefix) {
            Matcher matcher = Pattern.compile("^" + p).matcher(msisdn);
            if (matcher.find()) {
                return true;
            }
        }
        return false;
    }

    private String verifiedTransactionId = "";
    private String verifiedAmount;
    private String verifiedCurrency;

    public String getVerifiedTransactionId() {
        return verifiedTransactionId;
    }

    public void requireMatchingCommercialAttributes(java.math.BigDecimal amount, String currency) {
        if (verifiedAmount != null
                && amount.compareTo(new java.math.BigDecimal(verifiedAmount)) != 0)
            throw new net.citotech.cito.gateway.PaymentGatewayException(
                    "Airtel amount does not match payment");
        if (verifiedCurrency != null && !currency.equalsIgnoreCase(verifiedCurrency))
            throw new net.citotech.cito.gateway.PaymentGatewayException(
                    "Airtel currency does not match payment");
    }

    public void setApiDetails(
            String global_url, String api_username, String api_password, String api_pin) {
        if (global_url != null && !global_url.trim().isEmpty()) {
            this.global_url = global_url.trim();
        }
        this.api_username = api_username;
        this.api_password = api_password;
        this.api_pin = api_pin;
    }

    public void setEndpointDetails(
            String tokenPath,
            String collectionsPath,
            String disbursementsPath,
            String balancePath,
            String collectionStatusPath,
            String disbursementStatusPath) {
        this.tokenPath = valueOrCurrent(tokenPath, this.tokenPath);
        this.collectionsPath = valueOrCurrent(collectionsPath, this.collectionsPath);
        this.disbursementsPath = valueOrCurrent(disbursementsPath, this.disbursementsPath);
        this.balancePath = valueOrCurrent(balancePath, this.balancePath);
        this.collectionStatusPath = valueOrCurrent(collectionStatusPath, this.collectionStatusPath);
        this.disbursementStatusPath =
                valueOrCurrent(disbursementStatusPath, this.disbursementStatusPath);
    }

    public void setTransactionContext(String environment, String country, String currency) {
        this.mode = environment == null ? "SANDBOX" : environment.trim().toUpperCase();
        if (country != null && !country.trim().isEmpty())
            this.country = country.trim().toUpperCase();
        if (currency != null && !currency.trim().isEmpty())
            this.base_currency = currency.trim().toUpperCase();
    }

    String tokenUrl() {
        return endpoint(tokenPath);
    }

    String collectionUrl() {
        return endpoint(collectionsPath);
    }

    String disbursementUrl() {
        return endpoint(disbursementsPath);
    }

    String balanceUrl() {
        return endpoint(balancePath);
    }

    String statusUrl(String segment, String reference) {
        String template =
                "collection".equalsIgnoreCase(segment)
                        ? collectionStatusPath
                        : disbursementStatusPath;
        String encodedRef = encodePathSegment(reference);
        if (template.contains("{reference}")) {
            return endpoint(template.replace("{reference}", encodedRef));
        }
        if (template.contains("{id}")) {
            return endpoint(template.replace("{id}", encodedRef));
        }
        return endpoint(appendPath(template, encodedRef + "/"));
    }

    public void setPublicKey(String publicKey) {
        if (publicKey != null && !publicKey.isEmpty()) {
            this.publicKey =
                    publicKey
                            .replace("-----BEGIN PUBLIC KEY-----", "")
                            .replace("-----END PUBLIC KEY-----", "")
                            .replaceAll("\\s+", "");
        }
    }

    public String getPublicKey() {
        return publicKey;
    }

    public static String getGatewayCurrencyCode() {
        return gateway_currency_code;
    }

    public static String getGatewayId() {
        return gateway_id;
    }

    public String getSegment() {
        return segment;
    }

    public void setSegment(String segment) {
        this.segment = segment;
    }

    @Override
    public Double getBalance() {
        return getBalance("collection");
    }

    @Override
    public Double getBalance(String account) {
        try {
            Token token = getToken();
            if (token == null) {
                return null;
            }
            Map<String, String> headers = standardHeaders(token);
            HttpRequestResponse response = Common.doHttpRequest("GET", balanceUrl(), "", headers);
            if (response != null && response.getStatusCode() == 401) {
                // Audit C2: see the matching comment in submit() above.
                Token refreshed = requestToken();
                if (refreshed != null) {
                    response =
                            Common.doHttpRequest(
                                    "GET", balanceUrl(), "", standardHeaders(refreshed));
                }
            }
            if (response == null
                    || response.getStatusCode() != 200
                    || response.getResponse().isEmpty()) {
                return null;
            }
            JSONObject json = new JSONObject(response.getResponse());
            if (!json.isNull("data")) {
                JSONObject data = json.getJSONObject("data");
                if (!data.isNull("balance")) {
                    return Double.parseDouble(data.getString("balance").replace(",", ""));
                }
            }
            return null;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    @Override
    public GateWayResponse doPayOut(Double amount, String payee, String ref, String narrative) {
        return doPayOut(java.math.BigDecimal.valueOf(amount), payee, ref, narrative);
    }

    public GateWayResponse doPayOut(
            java.math.BigDecimal amount, String payee, String ref, String narrative) {
        this.segment = "disbursement";
        try {
            JSONObject body = new JSONObject();
            body.put("pin", RSAUtil.encrypt(this.api_pin, this.publicKey));
            JSONObject transaction = new JSONObject();
            transaction.put("amount", amount);
            transaction.put("id", ref);
            body.put("transaction", transaction);
            JSONObject payeeObject = new JSONObject();
            payeeObject.put("msisdn", stripCountryCode(payee));
            body.put("payee", payeeObject);
            body.put("reference", narrative);
            return submit("POST", disbursementUrl(), body.toString(), ref);
        } catch (BadPaddingException
                | IllegalBlockSizeException
                | InvalidKeyException
                | NoSuchPaddingException
                | NoSuchAlgorithmException
                | JSONException e) {
            return gatewayErrorFromException(e, "FAILED", "doPayOut");
        }
    }

    @Override
    public GateWayResponse doPayIn(Double amount, String payer, String ref, String narrative) {
        return doPayIn(java.math.BigDecimal.valueOf(amount), payer, ref, narrative);
    }

    public GateWayResponse doPayIn(
            java.math.BigDecimal amount, String payer, String ref, String narrative) {
        this.segment = "collection";
        try {
            JSONObject body = new JSONObject();
            JSONObject transaction = new JSONObject();
            transaction.put("amount", amount);
            transaction.put("country", this.country);
            transaction.put("currency", this.base_currency);
            transaction.put("id", ref);
            body.put("transaction", transaction);
            JSONObject subscriber = new JSONObject();
            subscriber.put("currency", this.base_currency);
            subscriber.put("country", this.country);
            subscriber.put("msisdn", stripCountryCode(payer));
            body.put("subscriber", subscriber);
            body.put("reference", narrative);
            return submit("POST", collectionUrl(), body.toString(), ref);
        } catch (JSONException e) {
            return gatewayErrorFromException(e, "UNDETERMINED", "doPayIn");
        }
    }

    @Override
    public GateWayResponse checkStatus(String ref) {
        GateWayResponse response = submit("GET", statusUrl(this.segment, ref), "", ref);
        if (response != null
                && ("SUCCESSFUL".equals(response.getTransactionStatus())
                        || "FAILED".equals(response.getTransactionStatus()))
                && !ref.equals(getVerifiedTransactionId())) {
            response.setTransactionStatus("UNDETERMINED");
            response.setMessage(
                    "Provider status did not verify the submitted transaction reference");
        }
        return response;
    }

    @Override
    public AccountInfo getAccountInfo(String msisdn) {
        AccountInfo info = new AccountInfo();
        info.setMsisdn(msisdn);
        try {
            this.segment = "collection";
            Token token = this.getToken();
            if (token == null) return info;
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + token.getToken());
            headers.put("X-Country", this.country);
            headers.put("X-Currency", this.base_currency);

            String url = endpoint("/standard/v2/users/" + encodePathSegment(msisdn));
            HttpRequestResponse rs = Common.doHttpRequest("GET", url, "", headers);
            if (rs == null || rs.getStatusCode() != 200) return info;
            JSONObject r = new JSONObject(rs.getResponse());
            if (!r.isNull("data")) {
                JSONObject data = r.getJSONObject("data");
                if (!data.isNull("first_name")) info.setFirstName(data.getString("first_name"));
                if (!data.isNull("last_name")) info.setLastName(data.getString("last_name"));
                if (!data.isNull("is_barred"))
                    info.setStatus(data.getBoolean("is_barred") ? "BARRED" : "ACTIVE");
                if (!data.isNull("msisdn")) info.setMsisdn(data.getString("msisdn"));
            }
        } catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
        }
        return info;
    }

    public Token getToken() throws IOException, JSONException {
        net.citotech.cito.gateway.ProviderEndpointPolicy.requireOrigin(
                this.global_url,
                "SANDBOX".equalsIgnoreCase(this.mode)
                        ? net.citotech.cito.gateway.AirtelOpenApiCredentialSchema.SANDBOX_BASE_URL
                        : net.citotech.cito.gateway.AirtelOpenApiCredentialSchema
                                .PRODUCTION_BASE_URL);
        Token token = readToken();
        if (token == null
                || LocalDateTime.now().isAfter(token.created_on.plusMinutes(TOKEN_TTL_MINUTES))) {
            return requestToken();
        }
        return token;
    }

    public Token requestToken() throws JSONException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        JSONObject body = new JSONObject();
        body.put("client_id", this.api_username);
        body.put("client_secret", this.api_password);
        body.put("grant_type", "client_credentials");

        HttpRequestResponse response =
                Common.doHttpRequest("POST", tokenUrl(), body.toString(), headers);
        if (response == null
                || response.getStatusCode() != 200
                || response.getResponse().isEmpty()) {
            logger.error("Failed to get Airtel OpenAPI token");
            return null;
        }
        JSONObject tokenJson = new JSONObject(response.getResponse());
        String accessToken = tokenJson.optString("access_token", "").trim();
        if (accessToken.isEmpty()) {
            logger.error("Airtel OAuth response omitted access_token");
            return null;
        }
        long expiresIn = tokenJson.optLong("expires_in", TOKEN_TTL_MINUTES * 60L);
        LocalDateTime createdAt = LocalDateTime.now();
        saveToken(accessToken, expiresIn);
        return new Token(accessToken, createdAt);
    }

    private GateWayResponse submit(String method, String url, String data, String ref) {
        try {
            Token token = getToken();
            if (token == null) {
                GateWayResponse unresolved =
                        gatewayError("Failed to obtain Airtel OpenAPI token", "UNDETERMINED", "");
                unresolved.setOurUniqueTxId(ref);
                return unresolved;
            }
            HttpRequestResponse response =
                    Common.doHttpRequest(method, url, data, standardHeaders(token));
            if (response != null && response.getStatusCode() == 401) {
                // Audit C2: the provider rejected this token even though our own TTL-based check
                // (getToken()) considered it still valid - revoked early, clock skew, or a
                // provider-side session invalidation. Force a fresh token and retry exactly once
                // rather than failing a transaction we could still complete.
                Token refreshed = requestToken();
                if (refreshed != null) {
                    response = Common.doHttpRequest(method, url, data, standardHeaders(refreshed));
                }
            }
            if (response == null) {
                GateWayResponse unresolved =
                        gatewayError(
                                "Payment outcome is not yet confirmed; check status before retrying.",
                                "UNDETERMINED",
                                "AIRTEL_NO_RESPONSE");
                unresolved.setOurUniqueTxId(ref);
                return unresolved;
            }
            GateWayResponse gatewayResponse = new GateWayResponse();
            gatewayResponse.setHttpStatus(String.valueOf(response.getStatusCode()));
            gatewayResponse.setRequestTrace(response.toString());
            gatewayResponse.setOurUniqueTxId(ref);
            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
                applySuccessResponse(gatewayResponse, response);
            } else {
                gatewayResponse.setStatus("ERROR");
                int statusCode = response.getStatusCode();
                boolean uncertain =
                        "GET".equalsIgnoreCase(method)
                                || statusCode == 0
                                || statusCode == 408
                                || statusCode == 409
                                || statusCode == 429
                                || statusCode >= 500;
                gatewayResponse.setTransactionStatus(uncertain ? "UNDETERMINED" : "FAILED");
                // Audit C6: response.getResponse() is the RAW, unfiltered Airtel OpenAPI response
                // body -
                // it must never be handed to a merchant or persisted in a trace. Translate it
                // into a merchant-safe message and keep only the HTTP outcome in the trace.
                ProviderErrorTranslator.Translation translation =
                        ProviderErrorTranslator.translateProviderResponse(
                                response.getStatusCode(),
                                response.getResponse(),
                                extractResultCode(response.getResponse()));
                gatewayResponse.setMessage(
                        uncertain
                                ? "Payment outcome is not yet confirmed; check status before retrying."
                                : translation.merchantMessage());
            }
            return gatewayResponse;
        } catch (Exception e) {
            // Audit J7: e.getMessage() previously went straight into the merchant-facing message
            // field
            // below, and this exception was never logged anywhere - the real cause was neither
            // safely
            // surfaced nor actually captured for internal diagnosis. Log it here, and hand the
            // merchant
            // only a stable reason code plus a generic, safe message.
            logger.error("Airtel OpenAPI request failed ({})", e.getClass().getSimpleName());
            ProviderErrorTranslator.Translation translation =
                    ProviderErrorTranslator.translateInternalFailure(e);
            GateWayResponse errorResponse = new GateWayResponse();
            errorResponse.setOurUniqueTxId(ref);
            errorResponse.setHttpStatus("0");
            errorResponse.setStatus("ERROR");
            errorResponse.setTransactionStatus("UNDETERMINED");
            errorResponse.setMessage(
                    "Payment outcome is not yet confirmed; check status before retrying.");
            errorResponse.setRequestTrace(
                    translation.stableCode() + ": " + e.getClass().getSimpleName());
            return errorResponse;
        }
    }

    /**
     * Best-effort extraction of Airtel OpenAPI's {@code status.result_code} for translation
     * purposes only - never throws.
     */
    private String extractResultCode(String rawBody) {
        if (rawBody == null || rawBody.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(rawBody);
            if (!json.isNull("status")) {
                JSONObject status = json.getJSONObject("status");
                if (!status.isNull("result_code")) {
                    return status.getString("result_code");
                }
            }
        } catch (Exception ignored) {
            // Not parseable as the expected shape - translator falls back to httpStatus-only
            // classification.
        }
        return null;
    }

    private void applySuccessResponse(GateWayResponse gatewayResponse, HttpRequestResponse response)
            throws JSONException {
        gatewayResponse.setStatus("OK");
        gatewayResponse.setTransactionStatus("PENDING");
        gatewayResponse.setMessage("Request submitted to the network successfully.");
        if (response.getResponse() == null || response.getResponse().isEmpty()) {
            return;
        }
        JSONObject json = new JSONObject(response.getResponse());
        if (!json.isNull("data") && !json.getJSONObject("data").isNull("transaction")) {
            JSONObject transaction = json.getJSONObject("data").getJSONObject("transaction");
            verifiedTransactionId = transaction.optString("id", "");
            verifiedAmount =
                    transaction.has("amount") ? transaction.optString("amount", null) : null;
            verifiedCurrency =
                    transaction.has("currency") ? transaction.optString("currency", null) : null;
            String status = transaction.isNull("status") ? "" : transaction.getString("status");
            String networkId = transaction.optString("airtel_money_id", "");
            if (networkId.isBlank()) networkId = transaction.optString("reference_id", "");
            gatewayResponse.setNetworkId(networkId);
            if ("TS".equalsIgnoreCase(status)) {
                gatewayResponse.setTransactionStatus("SUCCESSFUL");
            } else if ("TF".equalsIgnoreCase(status)) {
                gatewayResponse.setTransactionStatus("FAILED");
            } else if ("TA".equalsIgnoreCase(status)) {
                gatewayResponse.setTransactionStatus("UNDETERMINED");
            }
        }
        String resultCode = "";
        if (!json.isNull("status")) {
            JSONObject status = json.getJSONObject("status");
            resultCode = status.isNull("result_code") ? "" : status.getString("result_code");
            if (isFailedErrorList(resultCode)) {
                gatewayResponse.setStatus("ERROR");
                gatewayResponse.setTransactionStatus(
                        "SUCCESSFUL".equals(gatewayResponse.getTransactionStatus())
                                ? "UNDETERMINED"
                                : "FAILED");
            }
        }
        // Merchant messages are controlled copy, never raw provider wording. An uncertain
        // outcome must not imply that retrying a financial request is safe. Internal traces
        // retain the provider evidence for authorised reconciliation.
        String transactionStatus = gatewayResponse.getTransactionStatus();
        if ("UNDETERMINED".equals(transactionStatus)) {
            gatewayResponse.setMessage(
                    "Payment outcome is not yet confirmed; check status before retrying.");
        } else if ("FAILED".equals(transactionStatus)) {
            ProviderErrorTranslator.Translation translation =
                    ProviderErrorTranslator.translateProviderResponse(
                            response.getStatusCode(),
                            response.getResponse(),
                            resultCode.isEmpty() ? null : resultCode);
            gatewayResponse.setMessage(translation.merchantMessage());
        } else {
            gatewayResponse.setMessage(
                    "SUCCESSFUL".equals(transactionStatus)
                            ? "Payment completed successfully."
                            : "Payment is pending provider confirmation.");
        }
    }

    private Token readToken() {
        // Tokens live only in the encrypted provider_tokens DB store (see
        // ProviderTokenStoreService) -
        // no plaintext on-disk cache.
        Optional<ProviderToken> databaseToken =
                ProviderTokenStoreRegistry.findValid(
                        gateway_id, tokenSegment(), tokenEnvironment());
        if (databaseToken.isPresent()) {
            return new Token(databaseToken.get().getTokenValue(), LocalDateTime.now());
        }
        return null;
    }

    private void saveToken(String accessToken, long expiresIn) {
        ProviderTokenStoreRegistry.save(
                gateway_id,
                tokenSegment(),
                tokenEnvironment(),
                accessToken,
                ProviderTokenScope.expiresAt(expiresIn));
    }

    private String tokenSegment() {
        return ProviderTokenScope.segment(
                this.segment,
                tokenUrl(),
                this.country,
                this.base_currency,
                this.api_username,
                this.api_password);
    }

    private Map<String, String> standardHeaders(Token token) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + token.getToken());
        headers.put("X-Country", this.country);
        headers.put("X-Currency", this.base_currency);
        return headers;
    }

    private String tokenEnvironment() {
        if (this.mode != null && this.mode.toUpperCase().contains("PROD")) {
            return "PRODUCTION";
        }
        if (this.global_url != null && this.global_url.toLowerCase().contains("openapi.airtel")) {
            return "PRODUCTION";
        }
        return "SANDBOX";
    }

    private String endpoint(String pathOrUrl) {
        String configured = valueOrCurrent(pathOrUrl, "");
        net.citotech.cito.gateway.ProviderEndpointPolicy.requireRelativePath(configured);
        String base =
                this.global_url == null || this.global_url.trim().isEmpty()
                        ? "https://openapiuat.airtel.africa"
                        : this.global_url.trim();
        return appendPath(base, configured);
    }

    private String appendPath(String base, String path) {
        if (path == null || path.trim().isEmpty()) {
            return base;
        }
        String cleanBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String cleanPath = path.startsWith("/") ? path : "/" + path;
        return cleanBase + cleanPath;
    }

    private String valueOrCurrent(String candidate, String current) {
        return candidate == null || candidate.trim().isEmpty() ? current : candidate.trim();
    }

    private String encodePathSegment(String value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String stripCountryCode(String msisdn) {
        if (msisdn == null) return null;
        String normalized = msisdn.trim().replace(" ", "").replace("-", "");
        if (normalized.startsWith("+")) normalized = normalized.substring(1);
        if (normalized.startsWith("256") && normalized.length() > 9) {
            return normalized.substring(3);
        }
        return normalized;
    }

    private GateWayResponse gatewayError(String message, String transactionStatus, String trace) {
        GateWayResponse response = new GateWayResponse();
        response.setHttpStatus("0");
        response.setMessage(message == null ? "Airtel OpenAPI request failed" : message);
        response.setStatus("ERROR");
        response.setTransactionStatus(transactionStatus);
        response.setRequestTrace(trace == null ? "" : trace);
        return response;
    }

    /**
     * Audit J7: builds an error response from a caught internal exception (JSON building, crypto
     * failure - not a provider HTTP error) without handing the raw exception message to the
     * merchant, and without the exception silently disappearing (previously neither logged nor
     * safely surfaced). The exception detail is logged and kept in requestTrace (internal-only) for
     * support diagnosis.
     */
    private GateWayResponse gatewayErrorFromException(
            Exception e, String transactionStatus, String operation) {
        logger.error("Airtel OpenAPI " + operation + " failed before any provider call", e);
        ProviderErrorTranslator.Translation translation =
                ProviderErrorTranslator.translateInternalFailure(e);
        GateWayResponse response = new GateWayResponse();
        response.setHttpStatus("0");
        response.setStatus("ERROR");
        response.setTransactionStatus(transactionStatus);
        response.setMessage(translation.merchantMessage());
        response.setRequestTrace(
                translation.stableCode()
                        + ": "
                        + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " - " + e.getMessage()));
        return response;
    }

    private Boolean isFailedErrorList(String error) {
        String[] errors = {
            "ESB000004",
            "ESB000008",
            "ESB000011",
            "ESB000014",
            "ESB000033",
            "ESB000034",
            "ESB000035",
            "ESB000036",
            "ESB000039",
            "ESB000045"
        };
        for (String code : errors) {
            if (code.equals(error)) {
                return true;
            }
        }
        return false;
    }

    public class Token {
        String token;
        LocalDateTime created_on;

        public Token(String token, LocalDateTime created_on) {
            this.token = token;
            this.created_on = created_on;
        }

        public String getToken() {
            return this.token;
        }

        public String toString() {
            return "Token: "
                    + this.token
                    + "\nCreated On: "
                    + this.created_on.format(Common.getDateTimeFormater());
        }
    }
}
