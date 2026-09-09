package net.citotech.cito.communication.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.citotech.cito.communication.routing.CommunicationRoutingController.RuleUpsertRequest;
import net.citotech.cito.communication.routing.CommunicationRoutingRepository.ProviderRow;
import net.citotech.cito.communication.routing.CommunicationRoutingRepository.RuleRow;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** Covers {@link CommunicationRoutingController}'s provider/rules/effective surfaces. */
class CommunicationRoutingControllerTest {

    @Test
    void providersListsCatalog() {
        CommunicationRoutingRepository repository = mock(CommunicationRoutingRepository.class);
        when(repository.providers()).thenReturn(List.of(new ProviderRow(1L, "LEGACY_SETTINGS", "Legacy settings-driven HTTP gateway", "SMS", "net.citotech.cito.communication.sms.LegacySettingsSmsGatewayAdapter", null, "sms_api_url/sms_api_parameters/sms_api_http_method/sms_gateway_name", "YES", "2026-08-09 00:00:00", "2026-08-09 00:00:00")));
        Map<String, Object> body = new CommunicationRoutingController(repository).providers();
        assertThat(body.get("code")).isEqualTo("000");
        assertThat((List<?>) body.get("providers")).hasSize(1);
    }

    @Test
    void rulesListsOrderedRules() {
        CommunicationRoutingRepository repository = mock(CommunicationRoutingRepository.class);
        when(repository.rules()).thenReturn(List.of(new RuleRow(1L, "SMS", null, 100, "LEGACY_SETTINGS", "YES", "2026-08-09 00:00:00", "2026-08-09 00:00:00")));
        Map<String, Object> body = new CommunicationRoutingController(repository).rules();
        assertThat(body.get("code")).isEqualTo("000");
        assertThat((List<?>) body.get("rules")).hasSize(1);
    }

    @Test
    void effectiveReturnsUnresolvedWhenNoRuleMatches() {
        CommunicationRoutingRepository repository = mock(CommunicationRoutingRepository.class);
        when(repository.effectiveRule("SMS", 7L)).thenReturn(Optional.empty());
        ResponseEntity<Map<String, Object>> response = new CommunicationRoutingController(repository).effective(7L, "SMS");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("resolved", false).containsEntry("reason", "ROUTE_NOT_CONFIGURED");
    }

    @Test
    void effectiveRejectsUnsupportedChannel() {
        CommunicationRoutingRepository repository = mock(CommunicationRoutingRepository.class);
        ResponseEntity<Map<String, Object>> response = new CommunicationRoutingController(repository).effective(7L, "FAX");
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("error", "UNSUPPORTED_CHANNEL");
    }

    @Test
    void effectiveRejectsNonPositiveMerchantId() {
        CommunicationRoutingRepository repository = mock(CommunicationRoutingRepository.class);
        ResponseEntity<Map<String, Object>> response = new CommunicationRoutingController(repository).effective(0L, "SMS");
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("error", "INVALID_MERCHANT_ID");
    }

    @Test
    void effectiveReturnsUnresolvedWhenWinningProviderIsMissing() {
        CommunicationRoutingRepository repository = mock(CommunicationRoutingRepository.class);
        RuleRow rule = new RuleRow(2L, "SMS", 7L, 10, "MISSING", "YES", "2026-08-09 00:00:00", "2026-08-09 00:00:00");
        when(repository.effectiveRule("SMS", 7L)).thenReturn(Optional.of(rule));
        when(repository.provider("MISSING", "SMS")).thenReturn(Optional.empty());
        ResponseEntity<Map<String, Object>> response = new CommunicationRoutingController(repository).effective(7L, "SMS");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("resolved", false).containsEntry("reason", "PROVIDER_NOT_CONFIGURED");
    }

    @Test
    void effectiveResolvesTheWinningRuleWithItsProvider() {
        CommunicationRoutingRepository repository = mock(CommunicationRoutingRepository.class);
        RuleRow rule = new RuleRow(2L, "SMS", 7L, 10, "YO_SMS", "YES", "2026-08-09 00:00:00", "2026-08-09 00:00:00");
        when(repository.effectiveRule("SMS", 7L)).thenReturn(Optional.of(rule));
        when(repository.provider("YO_SMS", "SMS")).thenReturn(Optional.of(new ProviderRow(2L, "YO_SMS", "Yo! SMS", "SMS", "net.citotech.cito.communication.sms.YoSmsGatewayAdapter", "https://sms.yo.co.ug", "yo_sms_* (B1B)", "YES", "2026-08-09 00:00:00", "2026-08-09 00:00:00")));
        ResponseEntity<Map<String, Object>> response = new CommunicationRoutingController(repository).effective(7L, "SMS");
        Map<String, Object> body = response.getBody();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(body).containsEntry("resolved", true);
        assertThat(((RuleRow) body.get("rule")).providerCode()).isEqualTo("YO_SMS");
        assertThat(((ProviderRow) body.get("provider")).providerCode()).isEqualTo("YO_SMS");
    }

    @Test
    void upsertRuleDelegatesAndReturnsTheSavedRow() {
        CommunicationRoutingRepository repository = mock(CommunicationRoutingRepository.class);
        RuleRow saved = new RuleRow(3L, "SMS", 7L, 10, "AFRICAS_TALKING", "YES", "2026-08-09 00:00:00", "2026-08-09 00:00:00");
        when(repository.upsertRule(any(), anyString(), any(), any(), anyString(), anyString())).thenReturn(saved);
        Map<String, Object> body = new CommunicationRoutingController(repository).upsertRule(new RuleUpsertRequest(null, "SMS", 7L, 10, "AFRICAS_TALKING", "YES"));
        assertThat(body.get("code")).isEqualTo("000");
        assertThat(((RuleRow) body.get("rule")).providerCode()).isEqualTo("AFRICAS_TALKING");
        verify(repository).upsertRule(any(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    void deleteRuleDelegatesById() {
        CommunicationRoutingRepository repository = mock(CommunicationRoutingRepository.class);
        Map<String, Object> body = new CommunicationRoutingController(repository).deleteRule(4L);
        assertThat(body.get("code")).isEqualTo("000");
        assertThat(body.get("deleted")).isEqualTo(4L);
        verify(repository).deleteRule(4L);
    }
}
