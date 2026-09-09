package net.citotech.cito.communication;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.api.v2.V2RequestSecurityException;
import net.citotech.cito.api.v2.V2RequestSecurityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API-first merchant Communications/SMS service. */
@RestController
@RequestMapping(path = "/api/v2/communication/messages")
public class MerchantCommunicationController {

    private final MerchantCommunicationService communicationService;
    private final V2RequestSecurityService securityService;
    private final ObjectMapper objectMapper;

    public MerchantCommunicationController(
            MerchantCommunicationService communicationService,
            V2RequestSecurityService securityService,
            ObjectMapper objectMapper) {
        this.communicationService = communicationService;
        this.securityService = securityService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> send(@RequestBody String body, HttpServletRequest request) {
        try {
            SendRequest input = objectMapper.readValue(body, SendRequest.class);
            Merchant merchant = verifiedMerchant(request, body, input.merchantNumber());
            requireSms(input.channel());
            String idempotencyKey = request.getHeader("X-CPay-Idempotency-Key");
            return ResponseEntity.accepted().body(communicationService.enqueueSms(
                    merchant.getId(), input.recipient(), input.content(), input.purpose(),
                    input.externalReference(), idempotencyKey, input.expiresInSeconds(), options(input)));
        } catch (V2RequestSecurityException e) {
            return error(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Request authentication failed.");
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "COMMUNICATION_REJECTED", e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "COMMUNICATION_UNAVAILABLE", "Communication request could not be accepted.");
        }
    }

    /** Submit up to 1,000 recipients under one API request while preserving per-recipient delivery evidence. */
    @PostMapping(path = "/bulk", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> bulk(@RequestBody String body, HttpServletRequest request) {
        try {
            BulkRequest input = objectMapper.readValue(body, BulkRequest.class);
            Merchant merchant = verifiedMerchant(request, body, input.merchantNumber());
            if (input.recipients() == null || input.recipients().isEmpty()) {
                throw new IllegalArgumentException("recipients is required.");
            }
            if (input.recipients().size() > 1000) {
                throw new IllegalArgumentException("A bulk request can contain at most 1,000 recipients.");
            }
            String baseKey = request.getHeader("X-CPay-Idempotency-Key");
            List<Map<String, Object>> accepted = new ArrayList<>();
            for (int i = 0; i < input.recipients().size(); i++) {
                String key = blank(baseKey) ? null : truncate(baseKey + ":" + i, 128);
                String external = blank(input.externalReference())
                        ? null : truncate(input.externalReference() + ":" + i, 128);
                accepted.add(communicationService.enqueueSms(
                        merchant.getId(), input.recipients().get(i), input.content(), input.purpose(),
                        external, key, input.expiresInSeconds(), options(input)));
            }
            return ResponseEntity.accepted().body(Map.of(
                    "accepted", accepted.size(),
                    "messages", accepted));
        } catch (V2RequestSecurityException e) {
            return error(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Request authentication failed.");
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "COMMUNICATION_REJECTED", e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "COMMUNICATION_UNAVAILABLE", "Bulk communication request could not be accepted.");
        }
    }

    /** Returns encoding/segment/cost/routing information without sending the message. */
    @PostMapping(path = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> preview(@RequestBody String body, HttpServletRequest request) {
        try {
            PreviewRequest input = objectMapper.readValue(body, PreviewRequest.class);
            Merchant merchant = verifiedMerchant(request, body, input.merchantNumber());
            return ResponseEntity.ok(communicationService.previewSms(
                    merchant.getId(), input.content(), input.senderId(), input.countryCode(),
                    input.currencyCode(), input.routingStrategy(), truth(input.requireDeliveryReceipts()),
                    truth(input.requireInbound())));
        } catch (V2RequestSecurityException e) {
            return error(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Request authentication failed.");
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "COMMUNICATION_PREVIEW_REJECTED", e.getMessage());
        } catch (Exception e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "COMMUNICATION_UNAVAILABLE", "Communication preview could not be calculated.");
        }
    }

    /** Pure SMS encoding helper, useful to SDKs and client-side composers. */
    @PostMapping(path = "/analyze", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> analyze(@RequestBody AnalyzeRequest input) {
        return ResponseEntity.ok(communicationService.analyzeSms(input == null ? "" : input.content()));
    }

    @GetMapping(path = "/{reference}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> status(
            @PathVariable("reference") String reference,
            @RequestParam("merchantNumber") String merchantNumber,
            HttpServletRequest request) {
        try {
            Merchant merchant = verifiedMerchant(request, "", merchantNumber);
            Map<String, Object> result = communicationService.status(merchant.getId(), reference);
            if (result == null) {
                return error(HttpStatus.NOT_FOUND, "COMMUNICATION_NOT_FOUND", "Communication was not found.");
            }
            return ResponseEntity.ok(result);
        } catch (V2RequestSecurityException e) {
            return error(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Request authentication failed.");
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_COMMUNICATION_REQUEST", "Communication status could not be read.");
        }
    }

    private Merchant verifiedMerchant(HttpServletRequest request, String body, String merchantNumber) {
        if (blank(merchantNumber)) throw new IllegalArgumentException("merchantNumber is required.");
        Merchant merchant = securityService.verify(request, body, merchantNumber);
        if (merchant.getId() == null || merchant.getId() <= 0) {
            throw new V2RequestSecurityException("Merchant identity is unavailable.");
        }
        return merchant;
    }

    private void requireSms(String channel) {
        String normalized = blank(channel) ? "SMS" : channel.trim().toUpperCase();
        if (!"SMS".equals(normalized)) throw new IllegalArgumentException("This endpoint supports SMS.");
    }

    private MerchantCommunicationService.SmsOptions options(SmsFields input) {
        return new MerchantCommunicationService.SmsOptions(
                input.senderId(), input.scheduledAt(), input.routingStrategy(), input.countryCode(),
                input.currencyCode(), truth(input.requireDeliveryReceipts()), truth(input.requireInbound()),
                input.fallbackEnabled() == null || input.fallbackEnabled());
    }

    private boolean truth(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "code", code,
                "message", message == null ? "Request rejected" : message));
    }

    private interface SmsFields {
        String senderId();
        String scheduledAt();
        String routingStrategy();
        String countryCode();
        String currencyCode();
        Boolean requireDeliveryReceipts();
        Boolean requireInbound();
        Boolean fallbackEnabled();
    }

    public record SendRequest(
            String merchantNumber,
            String channel,
            String recipient,
            String content,
            String purpose,
            String externalReference,
            Integer expiresInSeconds,
            String senderId,
            String scheduledAt,
            String routingStrategy,
            String countryCode,
            String currencyCode,
            Boolean requireDeliveryReceipts,
            Boolean requireInbound,
            Boolean fallbackEnabled) implements SmsFields {}

    public record BulkRequest(
            String merchantNumber,
            List<String> recipients,
            String content,
            String purpose,
            String externalReference,
            Integer expiresInSeconds,
            String senderId,
            String scheduledAt,
            String routingStrategy,
            String countryCode,
            String currencyCode,
            Boolean requireDeliveryReceipts,
            Boolean requireInbound,
            Boolean fallbackEnabled) implements SmsFields {}

    public record PreviewRequest(
            String merchantNumber,
            String content,
            String senderId,
            String routingStrategy,
            String countryCode,
            String currencyCode,
            Boolean requireDeliveryReceipts,
            Boolean requireInbound) {}

    public record AnalyzeRequest(String content) {}
}
