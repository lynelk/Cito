package net.citotech.cito.communication.routing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.citotech.cito.admin.AdminAuditService;
import net.citotech.cito.communication.domain.CommunicationChannel;
import net.citotech.cito.communication.provider.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class CommunicationProviderActivationControllerTest {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final ProviderRegistry registry = mock(ProviderRegistry.class);
    final AdminAuditService audit = mock(AdminAuditService.class);
    final CommunicationProviderAdapter adapter = mock(CommunicationProviderAdapter.class);
    final CommunicationProviderActivationController api =
            new CommunicationProviderActivationController(jdbc, registry, audit);

    @BeforeEach
    void setup() {
        when(jdbc.queryForList(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("enabled_flag", "NO")));
        when(registry.find("SMSMOBILO_SMS", CommunicationChannel.SMS))
                .thenReturn(Optional.of(adapter));
        when(adapter.capabilities()).thenReturn(ProviderCapabilities.builder().send(true).build());
    }

    @Test
    void enablePersistsAndAudits() {
        assertEquals(
                "YES",
                api.activate(
                                "SMSMOBILO_SMS",
                                new CommunicationProviderActivationController.ActivationRequest(
                                        true))
                        .get("enabledFlag"));
        verify(jdbc).update(anyString(), eq(Map.of("code", "SMSMOBILO_SMS", "enabled", "YES")));
        verify(audit)
                .record(
                        "COMMUNICATION_MANAGE",
                        "COMMUNICATION_PROVIDER_ACTIVATION_CHANGED",
                        "SMSMOBILO_SMS",
                        "SMS provider enabled=true");
    }

    @Test
    void incompleteConfigurationCannotActivate() {
        when(adapter.capabilities()).thenReturn(ProviderCapabilities.builder().send(false).build());
        assertEquals(
                409,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        api.activate(
                                                "SMSMOBILO_SMS",
                                                new CommunicationProviderActivationController
                                                        .ActivationRequest(true)))
                        .getStatusCode()
                        .value());
        verify(jdbc, never()).update(anyString(), anyMap());
        verifyNoInteractions(audit);
    }

    @Test
    void disableWorksEvenWithoutAdapter() {
        when(jdbc.queryForList(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("enabled_flag", "YES")));
        assertEquals(
                "NO",
                api.activate(
                                "SMSMOBILO_SMS",
                                new CommunicationProviderActivationController.ActivationRequest(
                                        false))
                        .get("enabledFlag"));
        verifyNoInteractions(registry);
        verify(audit)
                .record(
                        anyString(),
                        anyString(),
                        eq("SMSMOBILO_SMS"),
                        eq("SMS provider enabled=false"));
    }

    @Test
    void repeatedSaveDoesNotCreateAnotherAuditChange() {
        api.activate(
                "SMSMOBILO_SMS",
                new CommunicationProviderActivationController.ActivationRequest(false));
        verify(jdbc, never()).update(anyString(), anyMap());
        verifyNoInteractions(audit);
    }

    @Test
    void unknownProviderCannotActivate() {
        when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());
        assertEquals(
                404,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        api.activate(
                                                "UNKNOWN",
                                                new CommunicationProviderActivationController
                                                        .ActivationRequest(true)))
                        .getStatusCode()
                        .value());
        verify(jdbc, never()).update(anyString(), anyMap());
    }

    @Test
    void stateMustBeExplicit() {
        assertEquals(
                400,
                assertThrows(
                                ResponseStatusException.class,
                                () ->
                                        api.activate(
                                                "SMSMOBILO_SMS",
                                                new CommunicationProviderActivationController
                                                        .ActivationRequest(null)))
                        .getStatusCode()
                        .value());
        verifyNoInteractions(jdbc, audit);
    }
}
