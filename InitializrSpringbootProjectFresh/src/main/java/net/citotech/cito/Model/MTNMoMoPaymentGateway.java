package net.citotech.cito.Model;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.citotech.cito.Common;
import net.citotech.cito.SettingsController;
import net.citotech.cito.gateway.ProviderToken;
import net.citotech.cito.gateway.ProviderTokenScope;
import net.citotech.cito.gateway.ProviderTokenStoreRegistry;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * @author josephtabajjwa
 */
public class MTNMoMoPaymentGateway extends PaymentGateway {
    String xml_sent = "";
    String xml_returned = "";
    String mode = "TEST";
    String global_url = "https://sandbox.momodeveloper.mtn.com";
    String env = "sandbox";
    String base_currency = "EUR";
    String segment = "collection"; // disbursement";

    public static String BALANCE_TYPE = "mtnmm_balance";

    String api_collections_user = "";
    String api_collections_key = "";
    String api_collections_subscription = "";

    String api_disbursements_user = "";
    String api_disbursements_key = "";
    String api_disbursements_subscription = "";

    public static String[] prefix = {"25677", "25678", "25676"};

    public static String gateway_id = "MTNMoMoPaymentGateway";

    public static String gateway_currency_code = "MTNMM";

    public static boolean isValidMisdn(String msisdn) {
        Boolean fromRoutingTable =
                net.citotech.cito.gateway.ChannelRoutingRegistry.matchesConfiguredPrefix(
                        gateway_id, msisdn);
        if (fromRoutingTable != null) {
            return fromRoutingTable;
        }
        for (int i = 0; i < prefix.length; i++) {
            String line = msisdn;
            String pattern = "^" + prefix[i] + "";
            Pattern r = Pattern.compile(pattern);
            Matcher m = r.matcher(line);
            if (m.find()) {
                return true;
            }
        }
        return false;
    }

    public void setApiDetails(
            String global_url,
            String api_collections_user,
            String api_collections_key,
            String api_collections_subscription,
            String api_disbursements_user,
            String api_disbursements_key,
            String api_disbursements_subscription,
            String env,
            String base_currency) {

        this.env = env == null ? "" : env.trim();
        this.global_url =
                "sandbox".equalsIgnoreCase(this.env)
                        ? net.citotech.cito.gateway.MtnMomoCredentialSchema.SANDBOX_BASE_URL
                        : net.citotech.cito.gateway.MtnMomoCredentialSchema.PRODUCTION_BASE_URL;
        this.api_collections_user = api_collections_user;
        this.api_collections_key = api_collections_key;
        this.api_collections_subscription = api_collections_subscription;
        this.api_disbursements_user = api_disbursements_user;
        this.api_disbursements_key = api_disbursements_key;
        this.api_disbursements_subscription = api_disbursements_subscription;
        this.base_currency = base_currency;
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

        throw new UnsupportedOperationException(
                "Not supported yet."); // To change body of generated methods, choose Tools |
        // Templates.
    }

    /*
     * @Param account: Set this to disbursement | collections
     */
    @Override
    public Double getBalance(String account) {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            String url_string = "";
            if (account.equals("collection")) {
                this.segment = "collection";
                headers.put("Ocp-Apim-Subscription-Key", this.api_collections_subscription);
                url_string = this.global_url + "/" + this.segment + "/v1_0/account/balance";
            } else {
                this.segment = "disbursement";
                headers.put("Ocp-Apim-Subscription-Key", this.api_disbursements_subscription);
                url_string = this.global_url + "/" + this.segment + "/v1_0/account/balance";
            }

            Token token;
            token = this.getToken();
            if (token == null) {
                GateWayResponse gwResponse = new GateWayResponse();
                gwResponse.setHttpStatus("0");
                gwResponse.setMessage("Failed to obtain token for " + this.segment);
                gwResponse.setStatus("ERROR");
                gwResponse.setTransactionStatus("UNDETERMINED");
                gwResponse.setRequestTrace("");
                return null;
            }

            headers.put("Authorization", "Bearer " + token.getToken());
            headers.put("X-Target-Environment", this.env);

            String data = "";

            GateWayResponse gwResponse = new GateWayResponse();

            HttpRequestResponse rs = executeWithTokenRetry("GET", url_string, data, headers, token);
            if (rs == null) {
                gwResponse.setHttpStatus("0");
                gwResponse.setMessage("Failed to obtain transaction status from the network.");
                gwResponse.setStatus("ERROR");
                gwResponse.setTransactionStatus("UNDETERMINED");
                gwResponse.setRequestTrace(safeTrace(url_string, 0, data));
                return null;
            }

            if (rs.getStatusCode() != 200) {
                Logger.getLogger(SettingsController.class.getName())
                        .log(
                                Level.SEVERE,
                                "MTN balance request failed with HTTP " + rs.getStatusCode());
                gwResponse.setHttpStatus(rs.getStatusCode() + "");

                String transaction_status = "";
                if (!rs.getResponse().isEmpty()) {
                    JSONObject rJson = new JSONObject(rs.getResponse());
                    if (!rJson.isNull("code") && rJson.getString("code").equals("RESOURCE_NOT_FOUND")) {
                        transaction_status = "FAILED";
                    }
                }

                gwResponse.setMessage(
                        "Provider request was not confirmed; check transaction status");
                gwResponse.setStatus("ERROR");
                gwResponse.setTransactionStatus(transaction_status);
                gwResponse.setNetworkId("");
                gwResponse.setRequestTrace(safeTrace(url_string, rs.getStatusCode(), data));
                return null;
            } else {
                gwResponse.setTransactionStatus("PENDING");
                gwResponse.setHttpStatus(rs.getStatusCode() + "");
                gwResponse.setMessage("Request submitted to the network successfully.");
                gwResponse.setStatus("OK");
                if (!rs.getResponse().isEmpty()) {
                    JSONObject rJson = new JSONObject(rs.getResponse());

                    if (!rJson.isNull("availableBalance")) {
                        String balance_string = rJson.getString("availableBalance");
                        double bal = Double.parseDouble(balance_string);
                        return bal;
                    }
                }

                return null;
            }
        } catch (JSONException ex) {
            Logger.getLogger(SettingsController.class.getName())
                    .log(Level.SEVERE, ex.getMessage(), "");
            return null;
        } catch (IOException ex) {
            Logger.getLogger(SettingsController.class.getName())
                    .log(Level.SEVERE, ex.getMessage(), "");
            return null;
        }
    }

    @Override
    public GateWayResponse doPayOut(Double amount, String payee, String ref, String narrative) {

        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            this.segment = "disbursement";

            Token token;
            token = this.getToken();
            if (token == null) return tokenFailure("disbursement");
            headers.put("Authorization", "Bearer " + token.getToken());
            headers.put("X-Reference-Id", ref);
            headers.put("X-Target-Environment", this.env);
            headers.put("Ocp-Apim-Subscription-Key", this.api_disbursements_subscription);

            JSONObject jdata = new JSONObject();
            jdata.put("amount", String.valueOf(amount));
            jdata.put("currency", this.base_currency);
            jdata.put("externalId", ref);

            JSONObject jdataPayer = new JSONObject();
            jdataPayer.put("partyIdType", "MSISDN");
            jdataPayer.put("partyId", payee);
            jdata.put("payee", jdataPayer);

            jdata.put("payerMessage", narrative(narrative));
            jdata.put("payeeNote", narrative(narrative));

            String data = jdata.toString();

            String url_string = this.global_url + "/" + this.segment + "/v1_0/transfer";

            GateWayResponse gwResponse = new GateWayResponse();

            HttpRequestResponse rs =
                    executeWithTokenRetry("POST", url_string, data, headers, token);
            if (rs == null) {

                gwResponse.setHttpStatus(null);
                gwResponse.setMessage("");
                gwResponse.setStatus("ERROR");
                gwResponse.setTransactionStatus("UNDETERMINED");
                gwResponse.setRequestTrace(safeTrace(url_string, 0, data));
                return gwResponse;
            }

            if (rs.getStatusCode() != 202) {
                Logger.getLogger(SettingsController.class.getName())
                        .log(Level.SEVERE, "MTN transfer failed with HTTP " + rs.getStatusCode());
                gwResponse.setHttpStatus(rs.getStatusCode() + "");

                gwResponse.setMessage(
                        "Provider request was not confirmed; check transaction status");
                gwResponse.setStatus("ERROR");
                gwResponse.setTransactionStatus(
                        net.citotech.cito.gateway.ProviderEndpointPolicy.ambiguousSubmission(
                                        rs.getStatusCode())
                                ? "UNDETERMINED"
                                : "FAILED");
                gwResponse.setRequestTrace(safeTrace(url_string, rs.getStatusCode(), data));
                return gwResponse;
            } else {
                gwResponse.setHttpStatus(rs.getStatusCode() + "");
                gwResponse.setMessage("Request submitted to the network successfully.");
                gwResponse.setStatus("OK");
                gwResponse.setTransactionStatus("PENDING");
                gwResponse.setRequestTrace(safeTrace(url_string, rs.getStatusCode(), data));
                return gwResponse;
            }
        } catch (JSONException ex) {
            Logger.getLogger(MTNMoMoPaymentGateway.class.getName()).log(Level.SEVERE, null, ex);
            GateWayResponse gwResponse = new GateWayResponse();
            gwResponse.setHttpStatus("0");
            gwResponse.setMessage("Provider status is currently unavailable");
            gwResponse.setStatus("ERROR");
            gwResponse.setTransactionStatus("UNDETERMINED");
            gwResponse.setRequestTrace("");
            return gwResponse;
        } catch (IOException ex) {
            GateWayResponse gwResponse = new GateWayResponse();
            gwResponse.setHttpStatus("0");
            gwResponse.setMessage("Provider status is currently unavailable");
            gwResponse.setStatus("ERROR");
            gwResponse.setTransactionStatus("UNDETERMINED");
            gwResponse.setRequestTrace("");
            return gwResponse;
        }
    }

    @Override
    public GateWayResponse checkStatus(String ref) {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            String url_string = "";
            if (this.segment.equals("collection")) {
                headers.put("Ocp-Apim-Subscription-Key", this.api_collections_subscription);
                url_string = this.global_url + "/" + this.segment + "/v1_0/requesttopay/" + ref;
            } else {
                headers.put("Ocp-Apim-Subscription-Key", this.api_disbursements_subscription);
                url_string = this.global_url + "/" + this.segment + "/v1_0/transfer/" + ref;
            }

            Token token;
            token = this.getToken();
            if (token == null) {
                GateWayResponse gwResponse = new GateWayResponse();
                gwResponse.setHttpStatus("0");
                gwResponse.setMessage("Failed to obtain token for " + this.segment);
                gwResponse.setStatus("ERROR");
                gwResponse.setTransactionStatus("UNDETERMINED");
                gwResponse.setRequestTrace("");
                return gwResponse;
            }

            headers.put("Authorization", "Bearer " + token.getToken());
            headers.put("X-Target-Environment", this.env);

            String data = "";

            GateWayResponse gwResponse = new GateWayResponse();

            HttpRequestResponse rs = executeWithTokenRetry("GET", url_string, data, headers, token);
            if (rs == null) {
                gwResponse.setHttpStatus("0");
                gwResponse.setMessage("Failed to obtain transaction status from the network.");
                gwResponse.setStatus("ERROR");
                gwResponse.setTransactionStatus("UNDETERMINED");
                gwResponse.setRequestTrace(safeTrace(url_string, 0, data));
                return gwResponse;
            }

            if (rs.getStatusCode() != 200) {
                Logger.getLogger(SettingsController.class.getName())
                        .log(
                                Level.SEVERE,
                                "MTN status request failed with HTTP " + rs.getStatusCode());
                gwResponse.setHttpStatus(rs.getStatusCode() + "");

                String transaction_status = "";
                if (!rs.getResponse().isEmpty()) {
                    JSONObject rJson = new JSONObject(rs.getResponse());
                    if (!rJson.isNull("code") && rJson.getString("code").equals("RESOURCE_NOT_FOUND")) {
                        transaction_status = "FAILED";
                    }
                }

                gwResponse.setMessage(
                        "Provider request was not confirmed; check transaction status");
                gwResponse.setStatus("ERROR");
                gwResponse.setTransactionStatus(transaction_status);
                gwResponse.setNetworkId("");
                gwResponse.setRequestTrace(safeTrace(url_string, rs.getStatusCode(), data));
                return gwResponse;
            } else {
                gwResponse.setTransactionStatus("PENDING");
                gwResponse.setHttpStatus(rs.getStatusCode() + "");
                gwResponse.setMessage("Request submitted to the network successfully.");
                gwResponse.setStatus("OK");
                if (!rs.getResponse().isEmpty()) {
                    JSONObject rJson = new JSONObject(rs.getResponse());
                    String tx_stataus = "";
                    if (!rJson.isNull("status")) {
                        tx_stataus = rJson.getString("status");
                        if (tx_stataus.toUpperCase().equals("SUCCESSFUL")) {
                            gwResponse.setTransactionStatus("SUCCESSFUL");
                        } else if (tx_stataus.toUpperCase().equals("FAILED")) {
                            gwResponse.setTransactionStatus("FAILED");
                        } else {
                            gwResponse.setTransactionStatus("UNDETERMINED");
                        }
                    }
                    if (!rJson.isNull("financialTransactionId")) {
                        gwResponse.setNetworkId(rJson.getString("financialTransactionId"));
                    }
                }

                gwResponse.setRequestTrace(safeTrace(url_string, rs.getStatusCode(), data));
                return gwResponse;
            }
        } catch (JSONException ex) {
            Logger.getLogger(SettingsController.class.getName())
                    .log(Level.SEVERE, ex.getMessage(), "");
            GateWayResponse gwResponse = new GateWayResponse();
            gwResponse.setHttpStatus("0");
            gwResponse.setMessage("Provider status is currently unavailable");
            gwResponse.setStatus("ERROR");
            gwResponse.setTransactionStatus("UNDETERMINED");
            gwResponse.setRequestTrace("");
            return gwResponse;
        } catch (IOException ex) {
            Logger.getLogger(SettingsController.class.getName())
                    .log(Level.SEVERE, ex.getMessage(), "");
            GateWayResponse gwResponse = new GateWayResponse();
            gwResponse.setHttpStatus("0");
            gwResponse.setMessage("Provider status is currently unavailable");
            gwResponse.setStatus("ERROR");
            gwResponse.setTransactionStatus("UNDETERMINED");
            gwResponse.setRequestTrace("");
            return gwResponse;
        }
    }

    @Override
    public GateWayResponse doPayIn(Double amount, String payer, String ref, String narrative) {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            this.segment = "collection";

            Token token;
            token = this.getToken();
            if (token == null) return tokenFailure("collection");
            headers.put("Authorization", "Bearer " + token.getToken());
            headers.put("X-Reference-Id", ref);
            headers.put("X-Target-Environment", this.env);
            headers.put("Ocp-Apim-Subscription-Key", this.api_collections_subscription);

            JSONObject jdata = new JSONObject();
            jdata.put("amount", String.valueOf(amount));
            jdata.put("currency", this.base_currency);
            jdata.put("externalId", ref);

            JSONObject jdataPayer = new JSONObject();
            jdataPayer.put("partyIdType", "MSISDN");
            jdataPayer.put("partyId", payer);
            jdata.put("payer", jdataPayer);

            jdata.put("payerMessage", narrative(narrative));
            jdata.put("payeeNote", narrative(narrative));

            String data = jdata.toString();

            String url_string = this.global_url + "/" + this.segment + "/v1_0/requesttopay";

            GateWayResponse gwResponse = new GateWayResponse();

            HttpRequestResponse rs =
                    executeWithTokenRetry("POST", url_string, data, headers, token);
            if (rs == null) {

                gwResponse.setHttpStatus("0");
                gwResponse.setMessage("HttpRequestResponse object is null.");
                gwResponse.setStatus("ERROR");
                gwResponse.setTransactionStatus("UNDETERMINED");
                gwResponse.setNetworkId("");
                gwResponse.setRequestTrace(safeTrace(url_string, 0, data));
                return gwResponse;
            }

            if (rs.getStatusCode() != 202) {
                Logger.getLogger(SettingsController.class.getName())
                        .log(
                                Level.SEVERE,
                                "MTN request-to-pay failed with HTTP " + rs.getStatusCode());
                gwResponse.setHttpStatus(rs.getStatusCode() + "");

                gwResponse.setMessage(
                        "Provider request was not confirmed; check transaction status");
                gwResponse.setStatus("ERROR");
                gwResponse.setTransactionStatus(
                        net.citotech.cito.gateway.ProviderEndpointPolicy.ambiguousSubmission(
                                        rs.getStatusCode())
                                ? "UNDETERMINED"
                                : "FAILED");
                gwResponse.setRequestTrace(safeTrace(url_string, rs.getStatusCode(), data));
                return gwResponse;
            } else {
                gwResponse.setHttpStatus(rs.getStatusCode() + "");
                gwResponse.setMessage("Request submitted to the network successfully.");
                gwResponse.setStatus("OK");
                gwResponse.setTransactionStatus("PENDING");
                gwResponse.setRequestTrace(safeTrace(url_string, rs.getStatusCode(), data));
                return gwResponse;
            }
        } catch (JSONException ex) {
            Logger.getLogger(MTNMoMoPaymentGateway.class.getName())
                    .log(Level.SEVERE, ex.getMessage(), ex);
            GateWayResponse gwResponse = new GateWayResponse();
            gwResponse.setHttpStatus("0");
            gwResponse.setMessage("Provider status is currently unavailable");
            gwResponse.setStatus("ERROR");
            gwResponse.setTransactionStatus("UNDETERMINED");
            gwResponse.setRequestTrace("Provider response could not be processed");
            return gwResponse;
        } catch (IOException ex) {
            Logger.getLogger(MTNMoMoPaymentGateway.class.getName())
                    .log(Level.SEVERE, ex.getMessage(), ex);
            GateWayResponse gwResponse = new GateWayResponse();
            gwResponse.setHttpStatus("0");
            gwResponse.setMessage("Provider status is currently unavailable");
            gwResponse.setStatus("ERROR");
            gwResponse.setTransactionStatus("UNDETERMINED");
            gwResponse.setRequestTrace("Provider response could not be processed");
            return gwResponse;
        }
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
            headers.put("Ocp-Apim-Subscription-Key", this.api_collections_subscription);
            headers.put("X-Target-Environment", this.env);

            String url =
                    this.global_url
                            + "/collection/v1_0/accountholder/msisdn/"
                            + msisdn
                            + "/basicuserinfo";
            HttpRequestResponse rs = executeWithTokenRetry("GET", url, "", headers, token);
            if (rs == null || rs.getStatusCode() != 200) return info;
            JSONObject r = new JSONObject(rs.getResponse());
            if (!r.isNull("name")) info.setProvided_name(r.getString("name"));
            if (!r.isNull("given_name")) info.setFirstName(r.getString("given_name"));
            if (!r.isNull("family_name")) info.setLastName(r.getString("family_name"));
            if (!r.isNull("status")) info.setStatus(r.getString("status"));
        } catch (Exception ex) {
            Logger.getLogger(MTNMoMoPaymentGateway.class.getName())
                    .log(Level.SEVERE, ex.getMessage(), ex);
        }
        return info;
    }

    public Token getToken() throws IOException {
        net.citotech.cito.gateway.ProviderEndpointPolicy.requireOrigin(
                this.global_url,
                "sandbox".equalsIgnoreCase(this.env)
                        ? net.citotech.cito.gateway.MtnMomoCredentialSchema.SANDBOX_BASE_URL
                        : net.citotech.cito.gateway.MtnMomoCredentialSchema.PRODUCTION_BASE_URL);
        Optional<ProviderToken> databaseToken =
                ProviderTokenStoreRegistry.findValid(
                        gateway_id, tokenSegment(), tokenEnvironment());
        if (databaseToken.isPresent()) {
            return new Token(databaseToken.get().getTokenValue(), LocalDateTime.now());
        }
        return this.requestToken();
    }

    public Token requestToken() throws JSONException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        if (segment.equals("collection")) {
            headers.put(
                    "Authorization",
                    "Basic "
                            + Common.base64Encode(
                                    this.api_collections_user + ":" + this.api_collections_key));
            headers.put("Ocp-Apim-Subscription-Key", this.api_collections_subscription);
        } else {
            headers.put(
                    "Authorization",
                    "Basic "
                            + Common.base64Encode(
                                    this.api_disbursements_user
                                            + ":"
                                            + this.api_disbursements_key));
            headers.put("Ocp-Apim-Subscription-Key", this.api_disbursements_subscription);
        }
        headers.put("X-Target-Environment", this.env);

        String url_string = this.global_url + "/" + this.segment + "/token/";

        HttpRequestResponse rs = Common.doHttpRequest("POST", url_string, "", headers);
        if (rs == null) {
            Logger.getLogger(SettingsController.class.getName())
                    .log(Level.SEVERE, "Failed to get token. ", "");
            return null;
        }

        if (rs.getStatusCode() != 200) {
            Logger.getLogger(SettingsController.class.getName())
                    .log(Level.SEVERE, "MTN token request failed with HTTP " + rs.getStatusCode());
            return null;
        } else {
            JSONObject jsToken = new JSONObject(rs.getResponse());
            String accessToken = jsToken.getString("access_token");
            LocalDateTime d = LocalDateTime.now();
            long expiresIn = jsToken.optLong("expires_in", 3600L);
            ProviderTokenStoreRegistry.save(
                    gateway_id,
                    tokenSegment(),
                    tokenEnvironment(),
                    accessToken,
                    ProviderTokenScope.expiresAt(expiresIn));
            return new Token(accessToken, d);
        }
    }

    private static final ReentrantLock[] TOKEN_REFRESH_LOCKS = new ReentrantLock[64];

    static {
        for (int i = 0; i < TOKEN_REFRESH_LOCKS.length; i++)
            TOKEN_REFRESH_LOCKS[i] = new ReentrantLock();
    }

    private static ReentrantLock tokenRefreshLock(
            String gatewayId, String segment, String environment) {
        int hash = (gatewayId + "|" + segment + "|" + environment).hashCode();
        return TOKEN_REFRESH_LOCKS[Math.floorMod(hash, TOKEN_REFRESH_LOCKS.length)];
    }

    private HttpRequestResponse executeWithTokenRetry(
            String method, String url, String data, Map<String, String> headers, Token token)
            throws JSONException {
        HttpRequestResponse response = Common.doHttpRequest(method, url, data, headers);
        if (response != null && response.getStatusCode() == 401 && token != null) {
            Token refreshed = forceRefreshToken(token.getToken());
            if (refreshed != null) {
                headers.put("Authorization", "Bearer " + refreshed.getToken());
                response = Common.doHttpRequest(method, url, data, headers);
            }
        }
        return response;
    }

    private Token forceRefreshToken(String failedTokenValue) throws JSONException {
        ReentrantLock lock = tokenRefreshLock(gateway_id, tokenSegment(), tokenEnvironment());
        lock.lock();
        try {
            Optional<ProviderToken> current =
                    ProviderTokenStoreRegistry.findValid(
                            gateway_id, tokenSegment(), tokenEnvironment());
            if (current.isPresent() && !current.get().getTokenValue().equals(failedTokenValue)) {
                return new Token(current.get().getTokenValue(), LocalDateTime.now());
            }
            return requestToken();
        } finally {
            lock.unlock();
        }
    }

    private String tokenSegment() {
        boolean collection = "collection".equals(this.segment);
        return ProviderTokenScope.segment(
                this.segment,
                this.global_url,
                this.env,
                this.base_currency,
                collection ? api_collections_user : api_disbursements_user,
                collection ? api_collections_key : api_disbursements_key,
                collection ? api_collections_subscription : api_disbursements_subscription);
    }

    private String tokenEnvironment() {
        if (this.mode != null && this.mode.toUpperCase().contains("PROD")) {
            return "PRODUCTION";
        }
        if (this.global_url != null
                && !this.global_url.toLowerCase().contains("sandbox")
                && !this.global_url.toLowerCase().contains("azure-api")) {
            return "PRODUCTION";
        }
        return "SANDBOX";
    }

    private GateWayResponse tokenFailure(String product) {
        GateWayResponse response = new GateWayResponse();
        response.setHttpStatus("0");
        response.setMessage("Failed to obtain MTN " + product + " token");
        response.setStatus("ERROR");
        response.setTransactionStatus("UNDETERMINED");
        response.setRequestTrace("");
        return response;
    }

    private String narrative(String value) {
        String text = value == null ? "" : value.trim();
        return text.length() <= 160 ? text : text.substring(0, 160);
    }

    private String safeTrace(String endpoint, int httpStatus, String requestBody) {
        return "endpoint="
                + endpoint
                + ";httpStatus="
                + httpStatus
                + ";requestHash="
                + Common.generateSha256String(requestBody == null ? "" : requestBody);
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
