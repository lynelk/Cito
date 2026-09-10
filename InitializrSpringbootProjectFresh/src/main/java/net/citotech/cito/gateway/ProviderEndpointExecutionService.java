package net.citotech.cito.gateway;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.citotech.cito.Common;
import net.citotech.cito.Model.AirtelMoneyOpenApiPaymentGateway;
import net.citotech.cito.Model.GateWayResponse;
import net.citotech.cito.Model.HttpRequestResponse;
import org.json.JSONObject;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProviderEndpointExecutionService {
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ProviderTokenStoreService tokenStoreService;
    private final ChannelCircuitBreaker circuitBreaker;

    public ProviderEndpointExecutionService(
            NamedParameterJdbcTemplate jdbcTemplate,
            ProviderTokenStoreService tokenStoreService,
            ChannelCircuitBreaker circuitBreaker) {
        this.jdbcTemplate = jdbcTemplate;
        this.tokenStoreService = tokenStoreService;
        this.circuitBreaker = circuitBreaker;
    }

    public ProviderBalanceResult fetchBalance(
            String channelCode,
            String accountRole,
            String environment,
            String country,
            String currency,
            Map<String, String> credentials) {
        try {
            if (MtnMomoCredentialSchema.CHANNEL_CODE.equalsIgnoreCase(channelCode)) {
                String operation =
                        "DISBURSEMENT".equalsIgnoreCase(accountRole) ? "PAYOUT" : "COLLECT";
                String token = mtnAccessToken(operation, credentials, environment, false);
                String prefix = MtnMomoCredentialSchema.productPrefix(operation);
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Authorization", "Bearer " + token);
                headers.put(
                        "Ocp-Apim-Subscription-Key",
                        required(
                                credentials.get(prefix + "SubscriptionKey"),
                                prefix + "SubscriptionKey"));
                headers.put(
                        "X-Target-Environment",
                        required(credentials.get("targetEnvironment"), "targetEnvironment"));
                String endpoint =
                        credentials.get("baseUrl").replaceAll("/+$", "")
                                + "/"
                                + prefix
                                + "/v1_0/account/balance";
                return balanceResponse(
                        Common.doHttpRequest("GET", endpoint, "", headers), "availableBalance");
            }
            if (AirtelOpenApiCredentialSchema.CHANNEL_CODE.equalsIgnoreCase(channelCode)) {
                AirtelOpenApiCredentialSchema.validate(credentials, environment, country, currency);
                JSONObject tokenBody =
                        new JSONObject()
                                .put("client_id", credentials.get("clientId"))
                                .put("client_secret", credentials.get("clientSecret"))
                                .put("grant_type", "client_credentials");
                String base = credentials.get("baseUrl").replaceAll("/+$", "");
                String tokenPath = credentials.getOrDefault("tokenPath", "/auth/oauth2/token");
                HttpRequestResponse tokenResponse =
                        Common.doHttpRequest(
                                "POST",
                                absolute(base, tokenPath),
                                tokenBody.toString(),
                                Map.of("Content-Type", "application/json"));
                if (tokenResponse == null || tokenResponse.getStatusCode() != 200) {
                    return new ProviderBalanceResult(
                            false, null, "Airtel OAuth token request failed");
                }
                String token =
                        new JSONObject(tokenResponse.getResponse()).optString("access_token", "");
                if (token.isBlank()) {
                    return new ProviderBalanceResult(
                            false, null, "Airtel OAuth response omitted access_token");
                }
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Authorization", "Bearer " + token);
                headers.put("Accept", "application/json");
                headers.put("X-Country", country);
                headers.put("X-Currency", currency);
                String balancePath =
                        credentials.getOrDefault("balancePath", "/standard/v2/users/balance");
                return balanceResponse(
                        Common.doHttpRequest("GET", absolute(base, balancePath), "", headers),
                        "balance");
            }
            return new ProviderBalanceResult(
                    false,
                    null,
                    "Direct balance synchronization is not supported for this channel");
        } catch (Exception e) {
            return new ProviderBalanceResult(
                    false,
                    null,
                    "Provider balance request failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    private ProviderBalanceResult balanceResponse(
            HttpRequestResponse response, String balanceField) {
        if (response == null || response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
            int status = response == null ? 0 : response.getStatusCode();
            return new ProviderBalanceResult(
                    false, null, "Provider balance endpoint returned HTTP " + status);
        }
        JSONObject json = new JSONObject(response.getResponse());
        String raw = json.optString(balanceField, "");
        if (raw.isBlank() && json.optJSONObject("data") != null) {
            raw = json.getJSONObject("data").optString(balanceField, "");
        }
        if (raw.isBlank()) {
            return new ProviderBalanceResult(
                    false, null, "Provider balance response omitted " + balanceField);
        }
        return new ProviderBalanceResult(
                true, new BigDecimal(raw.replace(",", "")), "Provider balance synchronized");
    }

    private String absolute(String base, String path) {
        if (path.startsWith("https://")) return path;
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    public record ProviderBalanceResult(boolean available, BigDecimal balance, String message) {}

    public GateWayResponse execute(
            String channelCode,
            String displayName,
            String operation,
            PaymentGatewayRequest request) {
        if (MtnMomoCredentialSchema.CHANNEL_CODE.equalsIgnoreCase(channelCode)) {
            return executeMtn(displayName, operation, request);
        }
        if (AirtelOpenApiCredentialSchema.CHANNEL_CODE.equalsIgnoreCase(channelCode)) {
            return executeAirtel(displayName, operation, request);
        }
        String endpointKey = operation.toLowerCase() + "Url";
        String endpointUrl = request.getMetadata().get(endpointKey);
        String gatewayState = request.getMetadata().getOrDefault("gatewayState", "SANDBOX");
        if (isBlank(endpointUrl)) {
            if ("PRODUCTION".equalsIgnoreCase(gatewayState)) {
                throw new PaymentGatewayException(
                        "Provider endpoint URL is required in production mode for "
                                + channelCode
                                + " "
                                + operation);
            }
            SandboxScenario scenario = sandboxScenario(operation, request.getAccountIdentifier());
            GateWayResponse accepted =
                    response(
                            channelCode,
                            displayName,
                            operation,
                            request,
                            scenario.httpStatus,
                            scenario.transactionStatus,
                            scenario.message);
            record(
                    channelCode,
                    operation,
                    request,
                    endpointKey,
                    scenario.httpStatus,
                    scenario.runStatus,
                    accepted.getMessage());
            return accepted;
        }

        // Per-channel circuit breaker (audit B5): a provider outage previously meant every
        // request paid the full connect/read timeout one at a time. Once enough consecutive
        // failures accrue, short-circuit further calls to this channel for a cooldown window
        // instead of hammering a known-down provider.
        if (!circuitBreaker.allowRequest(channelCode)) {
            String message =
                    "Circuit breaker is open for "
                            + channelCode
                            + " - provider calls are temporarily suspended";
            record(channelCode, operation, request, endpointUrl, 0, "CIRCUIT_OPEN", message);
            GateWayResponse shortCircuited =
                    response(channelCode, displayName, operation, request, 0, "FAILED", message);
            shortCircuited.setRequestTrace(
                    "circuitBreakerState=" + circuitBreaker.stateOf(channelCode));
            return shortCircuited;
        }

        String body = body(channelCode, operation, request);
        try {
            Map<String, String> requestHeaders = new LinkedHashMap<>();
            requestHeaders.put("Content-Type", "application/json");
            requestHeaders.put("X-CPay-Channel", channelCode);
            requestHeaders.put("X-CPay-Reference", request.getReference());
            tokenStoreService
                    .findValid(channelCode, operation, gatewayState)
                    .ifPresent(
                            token ->
                                    requestHeaders.put(
                                            "Authorization", "Bearer " + token.getTokenValue()));
            String headerName = request.getMetadata().get("authHeaderName");
            String headerValue = request.getMetadata().get("authHeaderValue");
            if (!isBlank(headerName) && !isBlank(headerValue)) {
                requestHeaders.put(headerName, headerValue);
            }
            HttpRequestResponse httpResponse =
                    Common.doHttpRequest("POST", endpointUrl, body, requestHeaders);
            int httpStatus = httpResponse.getStatusCode();
            String responseBody =
                    httpResponse.getResponse() == null ? "" : httpResponse.getResponse();
            String status = httpStatus >= 200 && httpStatus < 300 ? "SUBMITTED" : "FAILED";
            String runStatus = status;
            String recordMessage = responseBody;

            // Audit C9: Yo! Payments has no inbound webhook wired into CPay - collect/payout both
            // resolve through this synchronous HTTP response, and every response header used to be
            // discarded here, so a tampered/forged 2xx body would have been trusted outright.
            // Verify
            // the response signature (when the provider actually sent one) before honouring a
            // "success" status. Scoped to yo_payments specifically: it is the only channel driven
            // by
            // this service that has a signing secret (apiKey) already sitting in its merchant
            // channel
            // credentials with no other verification in front of it. See
            // YoPaymentsCallbackVerifier.
            if ("SUBMITTED".equals(status)
                    && YoPaymentsAdapter.CHANNEL_CODE.equalsIgnoreCase(channelCode)) {
                Map<String, String> responseHeaders =
                        httpResponse.getResponseHeaders() == null
                                ? Map.of()
                                : httpResponse.getResponseHeaders();
                if (!YoPaymentsCallbackVerifier.verify(
                        responseHeaders, responseBody, request.getMetadata())) {
                    status = "FAILED";
                    runStatus = "SIGNATURE_INVALID";
                    recordMessage =
                            "Provider response signature verification failed - response rejected as"
                                    + " untrusted";
                }
            }

            if ("SUBMITTED".equals(status)) {
                circuitBreaker.recordSuccess(channelCode);
            } else {
                circuitBreaker.recordFailure(channelCode);
            }
            record(
                    channelCode,
                    operation,
                    request,
                    endpointUrl,
                    httpStatus,
                    runStatus,
                    recordMessage);
            // Audit C6: on failure, `recordMessage` is the RAW, unfiltered provider response body -
            // never hand it to a merchant directly. The raw body is already preserved internally
            // either way, in the provider_endpoint_runs.response_summary row written by record()
            // just
            // above. SIGNATURE_INVALID is checked first and separately: it is not a business
            // decline
            // (see ProviderErrorTranslator#SIGNATURE_VERIFICATION_FAILED's javadoc) and must not be
            // run through the generic HTTP-status-based translation, which would otherwise conflate
            // it
            // with an ordinary provider decline and lose that distinction.
            String merchantMessage;
            if ("SIGNATURE_INVALID".equals(runStatus)) {
                merchantMessage =
                        ProviderErrorTranslator.SIGNATURE_VERIFICATION_FAILED.merchantMessage();
            } else if ("FAILED".equals(status)) {
                merchantMessage =
                        ProviderErrorTranslator.translateProviderResponse(httpStatus, responseBody)
                                .merchantMessage();
            } else {
                merchantMessage = trim(recordMessage);
            }
            GateWayResponse result =
                    response(
                            channelCode,
                            displayName,
                            operation,
                            request,
                            httpStatus,
                            status,
                            merchantMessage);
            result.setRequestTrace("requestHash=" + hash(body));
            return result;
        } catch (Exception e) {
            circuitBreaker.recordFailure(channelCode);
            record(channelCode, operation, request, endpointUrl, 0, "FAILED", e.getMessage());
            throw new PaymentGatewayException(
                    "Provider endpoint execution failed; check transaction status before retrying");
        }
    }

    private GateWayResponse executeMtn(
            String displayName, String operation, PaymentGatewayRequest request) {
        Map<String, String> credentials = request.getMetadata();
        String gatewayState = credentials.getOrDefault("gatewayState", "SANDBOX");
        MtnMomoCredentialSchema.validateForOperation(
                credentials,
                gatewayState,
                credentials.get("country"),
                credentials.get("currency"),
                operation);
        String baseUrl = credentials.get("baseUrl");
        String endpoint = MtnMomoCredentialSchema.endpoint(credentials, operation);
        if (isBlank(baseUrl)) {
            if ("PRODUCTION".equalsIgnoreCase(gatewayState)) {
                throw new PaymentGatewayException(
                        "MTN MoMo baseUrl is required in production mode");
            }
            SandboxScenario scenario = sandboxScenario(operation, request.getAccountIdentifier());
            GateWayResponse simulated =
                    response(
                            MtnMomoCredentialSchema.CHANNEL_CODE,
                            displayName,
                            operation,
                            request,
                            scenario.httpStatus,
                            scenario.transactionStatus,
                            scenario.message);
            record(
                    MtnMomoCredentialSchema.CHANNEL_CODE,
                    operation,
                    request,
                    "CPAY_SANDBOX_SIMULATOR",
                    scenario.httpStatus,
                    scenario.runStatus,
                    scenario.message);
            return simulated;
        }
        if (!circuitBreaker.allowRequest(MtnMomoCredentialSchema.CHANNEL_CODE)) {
            String message = "MTN MoMo provider calls are temporarily suspended";
            record(
                    MtnMomoCredentialSchema.CHANNEL_CODE,
                    operation,
                    request,
                    endpoint,
                    0,
                    "CIRCUIT_OPEN",
                    message);
            return response(
                    MtnMomoCredentialSchema.CHANNEL_CODE,
                    displayName,
                    operation,
                    request,
                    0,
                    "FAILED",
                    message);
        }

        String providerReference =
                credentials.getOrDefault("providerReference", UUID.randomUUID().toString());
        String requestBody = mtnBody(operation, request);
        try {
            String token = mtnAccessToken(operation, credentials, gatewayState, false);
            HttpRequestResponse providerResponse =
                    sendMtn(
                            operation,
                            request,
                            credentials,
                            endpoint,
                            providerReference,
                            requestBody,
                            token);
            if (providerResponse.getStatusCode() == 401) {
                token = mtnAccessToken(operation, credentials, gatewayState, true);
                providerResponse =
                        sendMtn(
                                operation,
                                request,
                                credentials,
                                endpoint,
                                providerReference,
                                requestBody,
                                token);
            }
            int httpStatus = providerResponse.getStatusCode();
            String responseBody =
                    providerResponse.getResponse() == null ? "" : providerResponse.getResponse();
            boolean accepted = httpStatus == 202;
            String runStatus =
                    accepted || ProviderEndpointPolicy.ambiguousSubmission(httpStatus)
                            ? "PENDING"
                            : "FAILED";
            if (accepted) {
                circuitBreaker.recordSuccess(MtnMomoCredentialSchema.CHANNEL_CODE);
                saveProviderReference(providerReference, request.getReference());
            } else {
                circuitBreaker.recordFailure(MtnMomoCredentialSchema.CHANNEL_CODE);
            }
            record(
                    MtnMomoCredentialSchema.CHANNEL_CODE,
                    operation,
                    request,
                    endpoint,
                    httpStatus,
                    runStatus,
                    responseBody);
            String message =
                    accepted
                            ? "Accepted by MTN MoMo; awaiting final callback or status poll"
                            : ProviderErrorTranslator.translateProviderResponse(
                                            httpStatus, responseBody)
                                    .merchantMessage();
            GateWayResponse result =
                    response(
                            MtnMomoCredentialSchema.CHANNEL_CODE,
                            displayName,
                            operation,
                            request,
                            httpStatus,
                            runStatus,
                            message);
            result.setNetworkId(providerReference);
            result.setRequestTrace("requestHash=" + safeHash(requestBody));
            return result;
        } catch (PaymentGatewayException e) {
            circuitBreaker.recordFailure(MtnMomoCredentialSchema.CHANNEL_CODE);
            record(
                    MtnMomoCredentialSchema.CHANNEL_CODE,
                    operation,
                    request,
                    endpoint,
                    0,
                    "FAILED",
                    e.getMessage());
            throw e;
        } catch (Exception e) {
            circuitBreaker.recordFailure(MtnMomoCredentialSchema.CHANNEL_CODE);
            record(
                    MtnMomoCredentialSchema.CHANNEL_CODE,
                    operation,
                    request,
                    endpoint,
                    0,
                    "FAILED",
                    e.getMessage());
            throw new PaymentGatewayException("MTN MoMo execution failed: " + e.getMessage());
        }
    }

    private GateWayResponse executeAirtel(
            String displayName, String operation, PaymentGatewayRequest request) {
        Map<String, String> credentials = request.getMetadata();
        String environment = credentials.getOrDefault("gatewayState", "SANDBOX");
        AirtelOpenApiCredentialSchema.validateForOperation(
                credentials,
                environment,
                credentials.get("country"),
                credentials.get("currency"),
                operation);
        if (!circuitBreaker.allowRequest(AirtelOpenApiCredentialSchema.CHANNEL_CODE)) {
            String message = "Airtel OpenAPI provider calls are temporarily suspended";
            record(
                    AirtelOpenApiCredentialSchema.CHANNEL_CODE,
                    operation,
                    request,
                    credentials.get("baseUrl"),
                    0,
                    "CIRCUIT_OPEN",
                    message);
            return response(
                    AirtelOpenApiCredentialSchema.CHANNEL_CODE,
                    displayName,
                    operation,
                    request,
                    0,
                    "FAILED",
                    message);
        }
        try {
            AirtelMoneyOpenApiPaymentGateway gateway = new AirtelMoneyOpenApiPaymentGateway();
            gateway.setApiDetails(
                    credentials.get("baseUrl"),
                    requiredAirtel(credentials.get("clientId"), "clientId"),
                    requiredAirtel(credentials.get("clientSecret"), "clientSecret"),
                    credentials.getOrDefault("apiPin", ""));
            gateway.setTransactionContext(
                    environment, credentials.get("country"), credentials.get("currency"));
            gateway.setEndpointDetails(
                    credentials.get("tokenPath"),
                    credentials.get("collectionPath"),
                    credentials.get("payoutPath"),
                    credentials.get("balancePath"),
                    credentials.get("collectionStatusPath"),
                    credentials.get("payoutStatusPath"));
            gateway.setPublicKey(credentials.get("publicKey"));
            GateWayResponse result =
                    "PAYOUT".equalsIgnoreCase(operation)
                            ? gateway.doPayOut(
                                    request.getAmountDecimal(),
                                    request.getAccountIdentifier(),
                                    request.getReference(),
                                    request.getDescription())
                            : gateway.doPayIn(
                                    request.getAmountDecimal(),
                                    request.getAccountIdentifier(),
                                    request.getReference(),
                                    request.getDescription());
            if (result != null
                    && isBlank(result.getNetworkId())
                    && ("PENDING".equalsIgnoreCase(result.getTransactionStatus())
                            || "UNDETERMINED".equalsIgnoreCase(result.getTransactionStatus()))) {
                result.setNetworkId(request.getReference());
            }
            if (result != null
                    && "200".equals(result.getHttpStatus())
                    && ("SUCCESSFUL".equals(result.getTransactionStatus())
                            || "FAILED".equals(result.getTransactionStatus()))) {
                if (!request.getReference().equals(gateway.getVerifiedTransactionId()))
                    result.setTransactionStatus("UNDETERMINED");
                else
                    gateway.requireMatchingCommercialAttributes(
                            request.getAmountDecimal(), request.getMetadata().get("currency"));
            }
            int httpStatus = parseStatus(result == null ? null : result.getHttpStatus());
            String transactionStatus =
                    result == null || result.getTransactionStatus() == null
                            ? "UNDETERMINED"
                            : result.getTransactionStatus();
            if (httpStatus >= 200
                    && httpStatus < 300
                    && !"FAILED".equalsIgnoreCase(transactionStatus)) {
                circuitBreaker.recordSuccess(AirtelOpenApiCredentialSchema.CHANNEL_CODE);
            } else {
                circuitBreaker.recordFailure(AirtelOpenApiCredentialSchema.CHANNEL_CODE);
            }
            record(
                    AirtelOpenApiCredentialSchema.CHANNEL_CODE,
                    operation,
                    request,
                    credentials.get("baseUrl"),
                    httpStatus,
                    transactionStatus,
                    result == null ? "No provider response" : result.getMessage());
            return result;
        } catch (PaymentGatewayException e) {
            circuitBreaker.recordFailure(AirtelOpenApiCredentialSchema.CHANNEL_CODE);
            throw e;
        } catch (Exception e) {
            circuitBreaker.recordFailure(AirtelOpenApiCredentialSchema.CHANNEL_CODE);
            record(
                    AirtelOpenApiCredentialSchema.CHANNEL_CODE,
                    operation,
                    request,
                    credentials.get("baseUrl"),
                    0,
                    "FAILED",
                    e.getClass().getSimpleName());
            throw new PaymentGatewayException("Airtel OpenAPI execution failed");
        }
    }

    private int parseStatus(String value) {
        try {
            return Integer.parseInt(value == null ? "0" : value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String requiredAirtel(String value, String field) {
        if (isBlank(value)) {
            throw new PaymentGatewayException("Airtel OpenAPI " + field + " is required");
        }
        return value.trim();
    }

    private HttpRequestResponse sendMtn(
            String operation,
            PaymentGatewayRequest request,
            Map<String, String> credentials,
            String endpoint,
            String providerReference,
            String requestBody,
            String token) {
        String prefix = MtnMomoCredentialSchema.productPrefix(operation);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.put(
                "Ocp-Apim-Subscription-Key",
                required(credentials.get(prefix + "SubscriptionKey"), prefix + "SubscriptionKey"));
        headers.put(
                "X-Target-Environment",
                required(credentials.get("targetEnvironment"), "targetEnvironment"));
        headers.put("X-Reference-Id", providerReference);
        headers.put("Content-Type", "application/json");
        String callbackUrl = credentials.get("callbackUrl");
        if (!isBlank(callbackUrl)) {
            headers.put(
                    "X-Callback-Url",
                    callbackUrl.trim().replaceAll("/+$", "") + "/" + providerReference);
        }
        return Common.doHttpRequest("POST", endpoint, requestBody, headers);
    }

    private String mtnAccessToken(
            String operation,
            Map<String, String> credentials,
            String environment,
            boolean forceRefresh) {
        ProviderEndpointPolicy.requireOrigin(
                credentials.get("baseUrl"),
                "SANDBOX".equalsIgnoreCase(environment)
                        ? MtnMomoCredentialSchema.SANDBOX_BASE_URL
                        : MtnMomoCredentialSchema.PRODUCTION_BASE_URL);
        String prefix = MtnMomoCredentialSchema.productPrefix(operation);
        String apiUser = required(credentials.get(prefix + "ApiUser"), prefix + "ApiUser");
        String apiKey = required(credentials.get(prefix + "ApiKey"), prefix + "ApiKey");
        String subscriptionKey =
                required(credentials.get(prefix + "SubscriptionKey"), prefix + "SubscriptionKey");
        String segment = MtnMomoCredentialSchema.tokenSegment(credentials, operation);
        if (!forceRefresh) {
            Optional<ProviderToken> cached =
                    tokenStoreService.findValid(
                            MtnMomoCredentialSchema.CHANNEL_CODE, segment, environment);
            if (cached != null && cached.isPresent()) return cached.get().getTokenValue();
        }

        Map<String, String> headers = new LinkedHashMap<>();
        String basic =
                Base64.getEncoder()
                        .encodeToString((apiUser + ":" + apiKey).getBytes(StandardCharsets.UTF_8));
        headers.put("Authorization", "Basic " + basic);
        headers.put("Ocp-Apim-Subscription-Key", subscriptionKey);
        HttpRequestResponse tokenResponse =
                Common.doHttpRequest(
                        "POST",
                        MtnMomoCredentialSchema.tokenEndpoint(credentials, operation),
                        "",
                        headers);
        if (tokenResponse == null
                || tokenResponse.getStatusCode() < 200
                || tokenResponse.getStatusCode() >= 300) {
            throw new PaymentGatewayException(
                    "MTN MoMo OAuth token request failed with HTTP "
                            + (tokenResponse == null ? 0 : tokenResponse.getStatusCode()));
        }
        JSONObject json = new JSONObject(tokenResponse.getResponse());
        String accessToken = json.optString("access_token", "").trim();
        if (accessToken.isEmpty()) {
            throw new PaymentGatewayException(
                    "MTN MoMo OAuth response did not include access_token");
        }
        long expiresIn = json.optLong("expires_in", 3600L);
        tokenStoreService.save(
                MtnMomoCredentialSchema.CHANNEL_CODE,
                segment,
                environment,
                accessToken,
                ProviderTokenScope.expiresAt(expiresIn));
        return accessToken;
    }

    private String mtnBody(String operation, PaymentGatewayRequest request) {
        String description = truncate(request.getDescription(), 160);
        JSONObject body =
                new JSONObject()
                        .put("amount", request.getAmountDecimal().toPlainString())
                        .put(
                                "currency",
                                required(request.getMetadata().get("currency"), "currency"))
                        .put("externalId", required(request.getReference(), "reference"))
                        .put("payerMessage", description)
                        .put("payeeNote", description);
        String partyKey = "PAYOUT".equalsIgnoreCase(operation) ? "payee" : "payer";
        body.put(
                partyKey,
                new JSONObject()
                        .put(
                                "partyIdType",
                                request.getMetadata().getOrDefault("partyIdType", "MSISDN"))
                        .put(
                                "partyId",
                                required(request.getAccountIdentifier(), "accountIdentifier")));
        return body.toString();
    }

    private void saveProviderReference(String providerReference, String merchantReference) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO provider_conversation_references (provider_code, conversation_id,"
                            + " tx_reference) VALUES (:provider,:conversation,:reference) ON DUPLICATE"
                            + " KEY UPDATE tx_reference=:reference",
                    new MapSqlParameterSource()
                            .addValue("provider", MtnMomoCredentialSchema.CHANNEL_CODE)
                            .addValue("conversation", providerReference)
                            .addValue("reference", merchantReference));
        } catch (Exception ignored) {
            // The provider request is already accepted. A missing correlation row must be surfaced
            // by reconciliation/status polling without converting the accepted request to failure.
        }
    }

    private String required(String value, String field) {
        if (isBlank(value)) throw new PaymentGatewayException("MTN MoMo " + field + " is required");
        return value.trim();
    }

    private String truncate(String value, int maxLength) {
        String safe = value == null ? "" : value.trim();
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
    }

    private GateWayResponse response(
            String channelCode,
            String displayName,
            String operation,
            PaymentGatewayRequest request,
            int httpStatus,
            String status,
            String message) {
        GateWayResponse response = new GateWayResponse();
        response.setStatus("FAILED".equals(status) ? "FAILED" : "SUCCESS");
        response.setTransactionStatus(status);
        response.setHttpStatus(String.valueOf(httpStatus));
        response.setMessage(displayName + " " + operation + " provider response: " + message);
        response.setNetworkId(channelCode + "-" + request.getReference());
        return response;
    }

    private void record(
            String channelCode,
            String operation,
            PaymentGatewayRequest request,
            String endpointUrl,
            int httpStatus,
            String status,
            String message) {
        String sql =
                "INSERT INTO provider_endpoint_runs (channel_code, operation_name, reference_value,"
                        + " endpoint_url, http_status, request_hash, response_summary, run_status,"
                        + " merchant_number, environment) VALUES (:channel_code, :operation_name,"
                        + " :reference_value, :endpoint_url, :http_status, :request_hash,"
                        + " :response_summary, :run_status, :merchant_number, :environment)";
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("channel_code", channelCode);
        p.addValue("operation_name", operation);
        p.addValue("reference_value", request.getReference());
        p.addValue("endpoint_url", endpointUrl);
        p.addValue("http_status", httpStatus);
        p.addValue(
                "request_hash",
                safeHash(channelCode + ":" + operation + ":" + request.getReference()));
        p.addValue("response_summary", trim(message));
        p.addValue("run_status", status);
        p.addValue("merchant_number", request.getMerchantNumber());
        p.addValue(
                "environment",
                request.getMetadata()
                        .getOrDefault(
                                "credentialEnvironment",
                                request.getMetadata().getOrDefault("gatewayState", "SANDBOX")));
        try {
            jdbcTemplate.update(sql, p);
        } catch (Exception ignored) {
        }
    }

    private SandboxScenario sandboxScenario(String operation, String accountIdentifier) {
        String account = accountIdentifier == null ? "" : accountIdentifier.trim();
        String op = operation == null ? "" : operation.trim().toUpperCase();
        if ("COLLECT".equals(op)) {
            if (account.endsWith("000002"))
                return new SandboxScenario(
                        202,
                        "FAILED",
                        "SANDBOX_FAILED",
                        "Sandbox customer declined the collection");
            if (account.endsWith("000003"))
                return new SandboxScenario(
                        202, "PENDING", "SANDBOX_PENDING", "Sandbox collection remains pending");
            if (account.endsWith("000004"))
                return new SandboxScenario(
                        202, "UNKNOWN", "SANDBOX_TIMEOUT", "Sandbox provider timeout scenario");
            if (account.endsWith("000005"))
                return new SandboxScenario(
                        400, "FAILED", "SANDBOX_REJECTED", "Sandbox account is unsupported");
            return new SandboxScenario(
                    202,
                    "SUCCESSFUL",
                    "SANDBOX_ACCEPTED",
                    "Sandbox collection accepted and resolved successfully");
        }
        if ("PAYOUT".equals(op)) {
            if (account.endsWith("000002"))
                return new SandboxScenario(
                        202, "FAILED", "SANDBOX_FAILED", "Sandbox payout failed");
            return new SandboxScenario(
                    202,
                    "SUCCESSFUL",
                    "SANDBOX_ACCEPTED",
                    "Sandbox payout accepted and resolved successfully");
        }
        return new SandboxScenario(
                202, "SUBMITTED", "SANDBOX_ACCEPTED", "Sandbox endpoint not configured");
    }

    private static class SandboxScenario {
        private final int httpStatus;
        private final String transactionStatus;
        private final String runStatus;
        private final String message;

        private SandboxScenario(
                int httpStatus, String transactionStatus, String runStatus, String message) {
            this.httpStatus = httpStatus;
            this.transactionStatus = transactionStatus;
            this.runStatus = runStatus;
            this.message = message;
        }
    }

    private String body(String channelCode, String operation, PaymentGatewayRequest request) {
        return "{\"channelCode\":\""
                + esc(channelCode)
                + "\",\"operation\":\""
                + esc(operation)
                + "\",\"merchantNumber\":\""
                + esc(request.getMerchantNumber())
                + "\",\"accountIdentifier\":\""
                + esc(request.getAccountIdentifier())
                + "\",\"amount\":"
                + request.getAmount()
                + ",\"reference\":\""
                + esc(request.getReference())
                + "\",\"description\":\""
                + esc(request.getDescription())
                + "\",\"callbackUrl\":\""
                + esc(request.getCallbackUrl())
                + "\"}";
    }

    private String hash(String value) throws Exception {
        return safeHash(value);
    }

    private String safeHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes =
                    digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) builder.append(String.format("%02x", b));
            return builder.toString();
        } catch (Exception e) {
            return "hash-error";
        }
    }

    private String trim(String value) {
        return value == null ? "" : (value.length() <= 1000 ? value : value.substring(0, 1000));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String esc(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
