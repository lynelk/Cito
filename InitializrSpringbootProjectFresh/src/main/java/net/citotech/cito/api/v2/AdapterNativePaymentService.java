package net.citotech.cito.api.v2;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.citotech.cito.Common;
import net.citotech.cito.Model.GateWayResponse;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.api.v2.dto.PaymentRequest;
import net.citotech.cito.api.v2.dto.PaymentResult;
import net.citotech.cito.gateway.GatewayExecutionService;
import net.citotech.cito.gateway.IntelligentPaymentRoutingService;
import net.citotech.cito.gateway.PaymentChannelAdapter;
import net.citotech.cito.gateway.PaymentChannelRegistry;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.gateway.PaymentGatewayRequest;
import net.citotech.cito.merchant.MerchantChannelCredentialService;
import net.citotech.cito.merchant.MerchantEnvironmentService;
import net.citotech.cito.money.MoneyAmount;
import net.citotech.cito.platform.CitoFeatureAccessService;
import net.citotech.cito.sharedprovider.SharedProviderAccessService;
import net.citotech.cito.sharedprovider.SharedProviderAccessService.CredentialContext;
import net.citotech.cito.treasury.ProviderTreasuryService;
import net.citotech.cito.treasury.ProviderTreasuryService.Reservation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AdapterNativePaymentService {
    private final PaymentChannelRegistry registry;
    private final MerchantChannelCredentialService channelCredentialService;
    private final MerchantEnvironmentService environmentService;
    private final GatewayExecutionService gatewayExecutionService;
    private final IntelligentPaymentRoutingService routingService;
    private final CitoFeatureAccessService featureAccessService;
    private final SharedProviderAccessService sharedProviderAccessService;
    private final ProviderTreasuryService treasuryService;
    private final String gatewayState;

    public AdapterNativePaymentService(
            PaymentChannelRegistry registry,
            MerchantChannelCredentialService channelCredentialService,
            MerchantEnvironmentService environmentService,
            GatewayExecutionService gatewayExecutionService,
            IntelligentPaymentRoutingService routingService,
            CitoFeatureAccessService featureAccessService,
            SharedProviderAccessService sharedProviderAccessService,
            ProviderTreasuryService treasuryService,
            @Value("${custom.gatewaystate:SANDBOX}") String gatewayState) {
        this.registry = registry;
        this.channelCredentialService = channelCredentialService;
        this.environmentService = environmentService;
        this.gatewayExecutionService = gatewayExecutionService;
        this.routingService = routingService;
        this.featureAccessService = featureAccessService;
        this.sharedProviderAccessService = sharedProviderAccessService;
        this.treasuryService = treasuryService;
        this.gatewayState = gatewayState;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private net.citotech.cito.gateway.MobileMoneyExecutionService mobileMoney;

    public String resolveEnvironment(String headerEnvironment, PaymentRequest request) {
        String requestEnvironment =
                request == null || request.getMetadata() == null
                        ? null
                        : request.getMetadata().get("environment");
        return environmentService.resolveRequestEnvironment(headerEnvironment, requestEnvironment);
    }

    public PaymentResult collect(PaymentRequest request, Merchant merchant, String environment) {
        validate(request, merchant, true, Common.API_MOBILE_MONEY_PAYIN);
        String account = request.getPayer().getValue();
        String resolvedEnvironment = environmentService.normalizedEnvironment(environment);
        requireMetadataEntitlements(request, merchant, resolvedEnvironment);
        BigDecimal amount = MoneyAmount.of(request.getAmount()).asBigDecimal();
        AdapterSelection selection =
                selectAdapterAndEnsureReady(
                        request, merchant, account, "COLLECT", resolvedEnvironment, amount);
        if (net.citotech.cito.gateway.MobileMoneyExecutionService.managed(
                selection.adapter().channelCode())) {
            request.setChannel(selection.adapter().channelCode());
            PaymentResult managedResult =
                    mobileMoney.submit(
                            request, merchant, resolvedEnvironment, "COLLECT", selection.adapter());
            routingService.recordMobileMoneyReplay(
                    selection.decisionReference(), managedResult.getStatus());
            return managedResult;
        }
        environmentService.enforceProductionLimit(merchant, resolvedEnvironment);
        CredentialContext credentialContext =
                sharedProviderAccessService.resolve(
                        merchant,
                        selection.adapter().channelCode(),
                        resolvedEnvironment,
                        request.getCountry(),
                        request.getCurrency(),
                        "COLLECT",
                        amount,
                        credentialSource(request));
        PaymentGatewayRequest gatewayRequest =
                adapterRequest(
                        request,
                        account,
                        merchant,
                        selection.adapter(),
                        resolvedEnvironment,
                        credentialContext);
        Reservation reservation =
                treasuryService.beginShared(
                        credentialContext,
                        merchant,
                        selection.adapter().channelCode(),
                        resolvedEnvironment,
                        amount,
                        request.getReference());
        long started = System.nanoTime();
        try {
            GateWayResponse response =
                    gatewayExecutionService.execute(
                            () -> selection.adapter().collect(gatewayRequest));
            treasuryService.completeShared(
                    reservation,
                    response == null ? null : response.getTransactionStatus(),
                    response == null ? null : response.getNetworkId());
            recordOutcome(selection, request, response, started);
            return result(request, selection.adapter(), resolvedEnvironment, response);
        } catch (RuntimeException e) {
            // A transport/runtime exception after an outbound provider call can be ambiguous. Keep
            // shared-provider exposure pending rather than falsely releasing CPay funds.
            treasuryService.completeShared(reservation, "PENDING", null);
            recordFailure(selection, request, started);
            throw e;
        }
    }

    public PaymentResult payout(PaymentRequest request, Merchant merchant, String environment) {
        validate(request, merchant, false, Common.API_MOBILE_MONEY_PAYOUT);
        String account = request.getPayee().getValue();
        String resolvedEnvironment = environmentService.normalizedEnvironment(environment);
        BigDecimal amount = MoneyAmount.of(request.getAmount()).asBigDecimal();
        AdapterSelection selection =
                selectAdapterAndEnsureReady(
                        request, merchant, account, "PAYOUT", resolvedEnvironment, amount);
        if (net.citotech.cito.gateway.MobileMoneyExecutionService.managed(
                selection.adapter().channelCode())) {
            request.setChannel(selection.adapter().channelCode());
            PaymentResult managedResult =
                    mobileMoney.submit(
                            request, merchant, resolvedEnvironment, "PAYOUT", selection.adapter());
            routingService.recordMobileMoneyReplay(
                    selection.decisionReference(), managedResult.getStatus());
            return managedResult;
        }
        environmentService.enforceProductionLimit(merchant, resolvedEnvironment);
        CredentialContext credentialContext =
                sharedProviderAccessService.resolve(
                        merchant,
                        selection.adapter().channelCode(),
                        resolvedEnvironment,
                        request.getCountry(),
                        request.getCurrency(),
                        "PAYOUT",
                        amount,
                        credentialSource(request));
        PaymentGatewayRequest gatewayRequest =
                adapterRequest(
                        request,
                        account,
                        merchant,
                        selection.adapter(),
                        resolvedEnvironment,
                        credentialContext);
        Reservation reservation =
                treasuryService.beginShared(
                        credentialContext,
                        merchant,
                        selection.adapter().channelCode(),
                        resolvedEnvironment,
                        amount,
                        request.getReference());
        long started = System.nanoTime();
        try {
            GateWayResponse response =
                    gatewayExecutionService.execute(
                            () -> selection.adapter().payout(gatewayRequest));
            treasuryService.completeShared(
                    reservation,
                    response == null ? null : response.getTransactionStatus(),
                    response == null ? null : response.getNetworkId());
            recordOutcome(selection, request, response, started);
            return result(request, selection.adapter(), resolvedEnvironment, response);
        } catch (RuntimeException e) {
            treasuryService.completeShared(reservation, "PENDING", null);
            recordFailure(selection, request, started);
            throw e;
        }
    }

    private void requireMetadataEntitlements(
            PaymentRequest request, Merchant merchant, String environment) {
        if (request == null
                || request.getMetadata() == null
                || merchant == null
                || merchant.getId() == null) {
            return;
        }
        String subscriptionReference = request.getMetadata().get("subscriptionReference");
        if (subscriptionReference != null && !subscriptionReference.isBlank()) {
            featureAccessService.require(merchant.getId(), "RECURRING_PAYMENTS", environment);
        }
    }

    private AdapterSelection selectAdapterAndEnsureReady(
            PaymentRequest request,
            Merchant merchant,
            String account,
            String operation,
            String environment,
            BigDecimal amount) {
        if (request.getChannel() != null && !request.getChannel().trim().isEmpty()) {
            PaymentChannelAdapter adapter =
                    registry.findByChannelCode(request.getChannel())
                            .orElseThrow(
                                    () ->
                                            new PaymentGatewayException(
                                                    "Unsupported channel: "
                                                            + request.getChannel()));
            if (!net.citotech.cito.gateway.MobileMoneyExecutionService.managed(
                            adapter.channelCode())
                    && !sharedProviderAccessService.isReady(
                            merchant,
                            adapter.channelCode(),
                            environment,
                            request.getCountry(),
                            request.getCurrency(),
                            operation,
                            amount,
                            credentialSource(request))) {
                throw new PaymentGatewayException(
                        "Channel is not ready for merchant-owned or CPay shared-provider execution: "
                                + adapter.channelCode());
            }
            return new AdapterSelection(adapter, null);
        }

        featureAccessService.require(merchant.getId(), "INTELLIGENT_ROUTING", environment);
        IntelligentPaymentRoutingService.RoutingPlan plan =
                routingService.rank(
                        request, merchant.getAccount_number(), operation, account, environment);
        PaymentGatewayException lastPreflightFailure = null;
        int attempted = 0;
        for (IntelligentPaymentRoutingService.RoutingCandidate candidate : plan.candidates()) {
            attempted++;
            try {
                if (!sharedProviderAccessService.isReady(
                        merchant,
                        candidate.adapter().channelCode(),
                        environment,
                        request.getCountry(),
                        request.getCurrency(),
                        operation,
                        amount,
                        credentialSource(request))) {
                    throw new PaymentGatewayException(
                            "Candidate has no ready merchant or shared-provider credential path");
                }
                String decisionReference =
                        routingService.recordDecision(
                                plan,
                                merchant.getId(),
                                merchant.getAccount_number(),
                                request,
                                operation,
                                environment,
                                candidate.adapter().channelCode(),
                                attempted == 1
                                        ? "Top-ranked channel passed merchant/shared-provider preflight"
                                        : "Selected fallback candidate "
                                                + attempted
                                                + " after preflight rejection");
                return new AdapterSelection(candidate.adapter(), decisionReference);
            } catch (PaymentGatewayException e) {
                lastPreflightFailure = e;
                if (!plan.policy().fallbackAllowed()) {
                    break;
                }
            }
        }
        if (lastPreflightFailure != null) {
            throw new PaymentGatewayException(
                    "No routed payment channel is configured and ready for merchant-owned or CPay shared-provider execution");
        }
        throw new PaymentGatewayException("No eligible payment channel is available");
    }

    private PaymentGatewayRequest adapterRequest(
            PaymentRequest request,
            String account,
            Merchant merchant,
            PaymentChannelAdapter adapter,
            String environment,
            CredentialContext credentialContext) {
        Object configuredCurrency = credentialContext.credentials().get("baseCurrency");
        if ("mtn_momo".equalsIgnoreCase(adapter.channelCode())
                && configuredCurrency != null
                && !String.valueOf(configuredCurrency)
                        .equalsIgnoreCase(String.valueOf(request.getCurrency()))) {
            throw new PaymentGatewayException(
                    "MTN request currency must match the configured baseCurrency");
        }
        Map<String, String> metadata = new HashMap<>();
        metadata.put("currency", request.getCurrency());
        metadata.put("country", request.getCountry());
        metadata.put("gatewayState", environment);
        metadata.put("credentialEnvironment", environment);
        metadata.put("applicationGatewayState", gatewayState);
        metadata.put("credentialSource", credentialContext.source());
        for (Map.Entry<String, Object> entry : credentialContext.credentials().entrySet()) {
            if (entry.getValue() != null) {
                metadata.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        return new PaymentGatewayRequest(
                request.getMerchantNumber(),
                account,
                MoneyAmount.of(request.getAmount()).asLegacyDouble(),
                request.getReference(),
                request.getDescription(),
                request.getCallbackUrl(),
                metadata);
    }

    private String credentialSource(PaymentRequest request) {
        return request == null || request.getMetadata() == null
                ? null
                : request.getMetadata().get("credentialSource");
    }

    private void validate(PaymentRequest request, Merchant merchant, boolean collect, String api) {
        if (request == null) {
            throw new PaymentGatewayException("Request body is required");
        }
        if (merchant == null || !merchant.getAccount_number().equals(request.getMerchantNumber())) {
            throw new PaymentGatewayException("Verified merchant does not match request merchant");
        }
        if (!"ACTIVE".equalsIgnoreCase(merchant.getStatus())) {
            throw new PaymentGatewayException("Merchant is not active");
        }
        if (collect && (request.getPayer() == null || blank(request.getPayer().getValue()))) {
            throw new PaymentGatewayException("payer.value is required");
        }
        if (!collect && (request.getPayee() == null || blank(request.getPayee().getValue()))) {
            throw new PaymentGatewayException("payee.value is required");
        }
        if (!allowed(merchant, api)) {
            throw new PaymentGatewayException("Merchant is not allowed to access " + api);
        }
        MoneyAmount.of(request.getAmount());
    }

    private boolean allowed(Merchant merchant, String api) {
        String[] allowedApis = merchant.getAllowed_apis();
        if (allowedApis == null) {
            return false;
        }
        for (String allowed : allowedApis) {
            if (api.equals(allowed)) {
                return true;
            }
        }
        return false;
    }

    private void recordOutcome(
            AdapterSelection selection,
            PaymentRequest request,
            GateWayResponse response,
            long startedNanos) {
        if (selection.decisionReference() == null) {
            return;
        }
        routingService.recordOutcome(
                selection.decisionReference(),
                selection.adapter().channelCode(),
                request.getCountry(),
                request.getCurrency(),
                responseSucceeded(response),
                elapsedMillis(startedNanos));
    }

    private void recordFailure(
            AdapterSelection selection, PaymentRequest request, long startedNanos) {
        if (selection.decisionReference() == null) {
            return;
        }
        routingService.recordOutcome(
                selection.decisionReference(),
                selection.adapter().channelCode(),
                request.getCountry(),
                request.getCurrency(),
                false,
                elapsedMillis(startedNanos));
    }

    private boolean responseSucceeded(GateWayResponse response) {
        if (response == null || response.getTransactionStatus() == null) {
            return false;
        }
        String status = response.getTransactionStatus().trim().toUpperCase(Locale.ROOT);
        return status.equals("SUCCESS")
                || status.equals("SUCCESSFUL")
                || status.equals("COMPLETED")
                || status.equals("COMPLETE")
                || status.equals("000");
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private PaymentResult result(
            PaymentRequest request,
            PaymentChannelAdapter adapter,
            String environment,
            GateWayResponse gatewayResponse) {
        PaymentResult result = new PaymentResult();
        result.setReference(request.getReference());
        result.setTransactionId(
                gatewayResponse == null ? request.getReference() : gatewayResponse.getNetworkId());
        result.setStatus(
                gatewayResponse == null ? "SUBMITTED" : gatewayResponse.getTransactionStatus());
        result.setChannel(adapter.channelCode());
        result.setEnvironment(environment);
        result.setCurrency(request.getCurrency());
        result.setMessage(gatewayResponse == null ? "Submitted" : gatewayResponse.getMessage());
        result.setProviderResponse(
                gatewayResponse == null ? "" : "httpStatus=" + gatewayResponse.getHttpStatus());
        return result;
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record AdapterSelection(PaymentChannelAdapter adapter, String decisionReference) {}
}
