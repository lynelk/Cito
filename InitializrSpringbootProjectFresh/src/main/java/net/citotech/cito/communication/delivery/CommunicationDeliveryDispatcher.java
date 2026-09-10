package net.citotech.cito.communication.delivery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.citotech.cito.communication.domain.CommunicationChannel;
import net.citotech.cito.communication.email.EmailDeliveryService;
import net.citotech.cito.communication.email.EmailSendRequest;
import net.citotech.cito.communication.email.EmailSendResult;
import net.citotech.cito.communication.provider.CommunicationProviderAdapter;
import net.citotech.cito.communication.provider.ProviderRegistry;
import net.citotech.cito.communication.provider.ProviderSendRequest;
import net.citotech.cito.communication.provider.ProviderSendResult;
import net.citotech.cito.communication.provider.SmsCommunicationProviderAdapter;
import net.citotech.cito.communication.routing.SmartSmsRoutingService;
import net.citotech.cito.communication.sms.SmsGatewayAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Provider-neutral outbound dispatcher with dynamic SMS smart routing. */
@Service
public class CommunicationDeliveryDispatcher {

    private static final Logger logger =
            Logger.getLogger(CommunicationDeliveryDispatcher.class.getName());

    private final DeliveryLogRepository deliveryLogRepository;
    private final ProviderRegistry providerRegistry;
    private final EmailDeliveryService emailDeliveryService;
    private final SmartSmsRoutingService smartSmsRoutingService;

    /** Compatibility constructor retained for existing tests and legacy callers. */
    public CommunicationDeliveryDispatcher(
            DeliveryLogRepository deliveryLogRepository,
            SmsGatewayAdapter smsGateway,
            EmailDeliveryService emailDeliveryService) {
        this(
                deliveryLogRepository,
                new ProviderRegistry(
                        List.of(
                                new SmsCommunicationProviderAdapter(smsGateway, "LEGACY_SETTINGS"),
                                new SmsCommunicationProviderAdapter(smsGateway, "YO_SMS"),
                                new SmsCommunicationProviderAdapter(smsGateway, "AFRICAS_TALKING"),
                                new SmsCommunicationProviderAdapter(smsGateway, "TWILIO_SMS"),
                                new SmsCommunicationProviderAdapter(smsGateway, "SMSMOBILO_SMS"))),
                emailDeliveryService,
                null);
    }

    /** Compatibility constructor retained for direct unit-test construction. */
    public CommunicationDeliveryDispatcher(
            DeliveryLogRepository deliveryLogRepository,
            ProviderRegistry providerRegistry,
            EmailDeliveryService emailDeliveryService) {
        this(deliveryLogRepository, providerRegistry, emailDeliveryService, null);
    }

    /** Production constructor. */
    @Autowired
    public CommunicationDeliveryDispatcher(
            DeliveryLogRepository deliveryLogRepository,
            ProviderRegistry providerRegistry,
            EmailDeliveryService emailDeliveryService,
            SmartSmsRoutingService smartSmsRoutingService) {
        this.deliveryLogRepository = deliveryLogRepository;
        this.providerRegistry = providerRegistry;
        this.emailDeliveryService = emailDeliveryService;
        this.smartSmsRoutingService = smartSmsRoutingService;
    }

    public DeliveryOutcome dispatch(
            long merchantId,
            String channel,
            String recipient,
            String subject,
            String content,
            String providerCode,
            Long referenceId) {
        return dispatch(
                merchantId,
                channel,
                recipient,
                subject,
                content,
                providerCode,
                referenceId,
                Map.of());
    }

    /**
     * Delivers one message. When an SMS has no provider pinned, Cito selects the best currently
     * eligible route at dispatch time. Scheduled traffic and retries therefore react to live cost,
     * capability and provider health.
     */
    public DeliveryOutcome dispatch(
            long merchantId,
            String channel,
            String recipient,
            String subject,
            String content,
            String providerCode,
            Long referenceId,
            Map<String, Object> metadata) {
        String normalizedChannel = normalizeChannel(channel);
        String resolvedProviderCode = trimToNull(providerCode);
        String routeExplanation = null;
        Map<String, Object> safeMetadata = metadata == null ? Map.of() : Map.copyOf(metadata);

        if ("SMS".equals(normalizedChannel)
                && resolvedProviderCode == null
                && smartSmsRoutingService != null) {
            SmartSmsRoutingService.RouteDecision decision;
            if (referenceId != null) {
                decision =
                        smartSmsRoutingService.selectForMessage(referenceId, merchantId, content);
            } else {
                decision =
                        smartSmsRoutingService.preview(
                                merchantId,
                                content,
                                text(safeMetadata, "countryCode"),
                                text(safeMetadata, "currencyCode"),
                                text(safeMetadata, "routingStrategy"),
                                bool(safeMetadata, "requireDeliveryReceipts"),
                                bool(safeMetadata, "requireInbound"));
            }
            resolvedProviderCode = decision.selectedProviderCode();
            routeExplanation = decision.explanation();
        }

        long deliveryId =
                deliveryLogRepository.insert(
                        merchantId,
                        normalizedChannel,
                        resolvedProviderCode,
                        null,
                        referenceId,
                        recipient);

        Object communicationId = safeMetadata.get("communicationId");
        if (communicationId instanceof Number id)
            deliveryLogRepository.linkCommunication(deliveryId, id.longValue());

        if ("SMS".equals(normalizedChannel) && resolvedProviderCode == null) {
            String trace =
                    routeExplanation == null || routeExplanation.isBlank()
                            ? "No eligible SMS provider is currently available"
                            : routeExplanation;
            deliveryLogRepository.updateStatus(deliveryId, DeliveryStatus.FAILED, trace, "");
            return new DeliveryOutcome(deliveryId, DeliveryStatus.FAILED, null);
        }

        try {
            DeliveryStatus status;
            String trace;
            String gwResponse;
            if ("EMAIL".equals(normalizedChannel)) {
                EmailSendResult result =
                        emailDeliveryService.send(
                                new EmailSendRequest(recipient, subject, content));
                status =
                        result.status() == EmailSendResult.Status.SENT
                                ? DeliveryStatus.SENT
                                : DeliveryStatus.FAILED;
                trace = result.trace();
                gwResponse = result.response();
            } else {
                CommunicationChannel channelEnum =
                        CommunicationChannel.fromString(normalizedChannel);
                CommunicationProviderAdapter adapter =
                        providerRegistry.find(resolvedProviderCode, channelEnum).orElse(null);
                if (adapter == null) {
                    status = DeliveryStatus.REJECTED;
                    trace =
                            normalizedChannel
                                    + " adapter not implemented for "
                                    + resolvedProviderCode;
                    gwResponse = "";
                } else {
                    ProviderSendResult result =
                            adapter.send(
                                    new ProviderSendRequest(
                                            referenceId == null ? 0L : referenceId,
                                            deliveryId,
                                            merchantId,
                                            recipient,
                                            subject,
                                            content,
                                            null,
                                            Map.of(),
                                            stringMetadata(safeMetadata)));
                    status = mapProviderStatus(result);
                    trace = result == null ? "No provider result" : result.trace();
                    gwResponse = result == null ? "" : result.safeResponse();
                    if (result != null && result.providerMessageId() != null) {
                        deliveryLogRepository.updateProviderMessageId(
                                deliveryId, result.providerMessageId());
                    }
                }
            }
            deliveryLogRepository.updateStatus(deliveryId, status, trace, gwResponse);
            return new DeliveryOutcome(deliveryId, status, resolvedProviderCode);
        } catch (Exception ex) {
            logger.log(
                    Level.WARNING,
                    "Delivery failed for channel "
                            + normalizedChannel
                            + " recipient "
                            + recipient
                            + ": "
                            + ex.getMessage(),
                    ex);
            deliveryLogRepository.updateStatus(
                    deliveryId, DeliveryStatus.FAILED, ex.getMessage(), "");
            return new DeliveryOutcome(deliveryId, DeliveryStatus.FAILED, resolvedProviderCode);
        }
    }

    private DeliveryStatus mapProviderStatus(ProviderSendResult result) {
        if (result == null || result.status() == null) return DeliveryStatus.FAILED;
        return switch (result.status()) {
            case ACCEPTED, SENT -> DeliveryStatus.SENT;
            case DELIVERED -> DeliveryStatus.DELIVERED;
            case REJECTED -> DeliveryStatus.REJECTED;
            case FAILED -> DeliveryStatus.FAILED;
            case UNKNOWN -> DeliveryStatus.UNKNOWN;
        };
    }

    private String normalizeChannel(String channel) {
        return channel == null || channel.isBlank() ? "SMS" : channel.trim().toUpperCase();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    private String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null || String.valueOf(value).isBlank()
                ? null
                : String.valueOf(value).trim();
    }

    private boolean bool(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Boolean b
                ? b
                : value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private Map<String, String> stringMetadata(Map<String, Object> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, String> converted = new LinkedHashMap<>();
        values.forEach(
                (key, value) -> {
                    if (key != null && value != null) converted.put(key, String.valueOf(value));
                });
        return Map.copyOf(converted);
    }

    public record DeliveryOutcome(long deliveryId, DeliveryStatus status, String providerCode) {}
}
