package net.citotech.cito.communication.routing;

import java.util.Map;
import net.citotech.cito.admin.AdminAuditService;
import net.citotech.cito.communication.domain.CommunicationChannel;
import net.citotech.cito.communication.provider.ProviderRegistry;
import net.citotech.cito.communication.provider.ProviderSendRequest;
import net.citotech.cito.communication.provider.ProviderSendResult;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Explicit, audited provider activation; saving a routing rule never activates a provider. */
@RestController
@RequestMapping("/api/v2/admin/communication/routing/providers")
@PreAuthorize("hasRole('ADMIN')")
public class CommunicationProviderActivationController {
    private final NamedParameterJdbcTemplate jdbc;
    private final ProviderRegistry registry;
    private final AdminAuditService audit;

    public CommunicationProviderActivationController(
            NamedParameterJdbcTemplate jdbc, ProviderRegistry registry, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.audit = audit;
    }

    @PostMapping("/{providerCode}/activation")
    @Transactional
    public Map<String, Object> activate(
            @PathVariable String providerCode, @RequestBody ActivationRequest request) {
        if (request.enabled() == null)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Explicit enabled state is required");
        var params = Map.of("code", providerCode);
        var rows =
                jdbc.queryForList(
                        "SELECT enabled_flag FROM communication_providers WHERE provider_code=:code AND channel='SMS' FOR UPDATE",
                        params);
        if (rows.size() != 1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SMS provider not found");
        if (request.enabled()) {
            var adapter =
                    registry.find(providerCode, CommunicationChannel.SMS)
                            .orElseThrow(
                                    () ->
                                            new ResponseStatusException(
                                                    HttpStatus.CONFLICT,
                                                    "Provider adapter is unavailable"));
            if (!adapter.capabilities().send())
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Provider send configuration is incomplete");
        }
        String enabled = request.enabled() ? "YES" : "NO";
        if (!enabled.equals(rows.get(0).get("enabled_flag"))) {
            jdbc.update(
                    "UPDATE communication_providers SET enabled_flag=:enabled WHERE provider_code=:code AND channel='SMS'",
                    Map.of("code", providerCode, "enabled", enabled));
            audit.record(
                    "COMMUNICATION_MANAGE",
                    "COMMUNICATION_PROVIDER_ACTIVATION_CHANGED",
                    providerCode,
                    "SMS provider enabled=" + request.enabled());
        }
        return Map.of("code", "000", "providerCode", providerCode, "enabledFlag", enabled);
    }

    /**
     * Sends one explicit platform-admin test SMS through the selected adapter. The test is isolated
     * from merchant communication history/billing by using the platform test context (merchantId=0)
     * and contains no merchant-supplied content. Provider acceptance is returned separately from
     * handset delivery, which still depends on the provider's delivery-receipt support.
     */
    @PostMapping("/{providerCode}/test-sms")
    public Map<String, Object> testSms(
            @PathVariable String providerCode, @RequestBody TestSmsRequest request) {
        String recipient =
                request == null || request.recipient() == null ? "" : request.recipient().trim();
        if (!recipient.matches("\\+?[0-9]{7,15}")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "A valid E.164-style recipient number is required");
        }

        var rows =
                jdbc.queryForList(
                        "SELECT enabled_flag FROM communication_providers WHERE provider_code=:code AND channel='SMS'",
                        Map.of("code", providerCode));
        if (rows.size() != 1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SMS provider not found");
        if (!"YES".equalsIgnoreCase(String.valueOf(rows.get(0).get("enabled_flag")))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Enable the SMS provider before sending a test message");
        }

        var adapter =
                registry.find(providerCode, CommunicationChannel.SMS)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.CONFLICT,
                                                "Provider adapter is unavailable"));
        if (!adapter.capabilities().send()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Provider send configuration is incomplete");
        }

        String senderId = request.senderId() == null ? "" : request.senderId().trim();
        ProviderSendResult result =
                adapter.send(
                        new ProviderSendRequest(
                                0L,
                                0L,
                                0L,
                                recipient,
                                null,
                                "Cito SMS gateway test",
                                null,
                                Map.of(),
                                senderId.isBlank()
                                        ? Map.of("test", "true")
                                        : Map.of("test", "true", "senderId", senderId)));

        audit.record(
                "COMMUNICATION_MANAGE",
                "COMMUNICATION_PROVIDER_TEST_SMS",
                providerCode,
                "SMS provider test result=" + result.status().name());

        return Map.of(
                "code",
                "000",
                "providerCode",
                providerCode,
                "status",
                result.status().name(),
                "normalizedCode",
                result.normalizedCode() == null ? "" : result.normalizedCode(),
                "providerMessageId",
                result.providerMessageId() == null ? "" : result.providerMessageId(),
                "providerAccepted",
                result.isBillable(),
                "deliveryConfirmed",
                result.status() == ProviderSendResult.Status.DELIVERED);
    }

    public record ActivationRequest(Boolean enabled) {}

    public record TestSmsRequest(String recipient, String senderId) {}
}
