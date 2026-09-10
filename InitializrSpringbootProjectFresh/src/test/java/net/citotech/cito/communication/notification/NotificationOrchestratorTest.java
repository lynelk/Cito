package net.citotech.cito.communication.notification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.citotech.cito.communication.preference.PreferenceService;
import net.citotech.cito.communication.sms.SmsEncodingService;
import net.citotech.cito.communication.template.TemplateService;
import net.citotech.cito.merchant.MerchantNotificationPreferenceService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class NotificationOrchestratorTest {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final MerchantNotificationPreferenceService preferences =
            mock(MerchantNotificationPreferenceService.class);
    final PreferenceService channels = mock(PreferenceService.class);
    final TemplateService templates = mock(TemplateService.class);
    final NotificationOrchestrator service =
            new NotificationOrchestrator(
                    jdbc,
                    preferences,
                    channels,
                    templates,
                    new SmsEncodingService(),
                    new ObjectMapper());

    void event(String type, String status) {
        when(jdbc.queryForMap(contains("FOR UPDATE"), any(MapSqlParameterSource.class)))
                .thenReturn(
                        Map.of(
                                "merchant_id",
                                42L,
                                "event_type",
                                type,
                                "source_reference",
                                "event-ref",
                                "status",
                                status));
        String key = NotificationEventCatalog.require(type).templateKey();
        when(templates.find(key, "SMS"))
                .thenReturn(
                        Optional.of(
                                new TemplateService.TemplateRow(
                                        1,
                                        key,
                                        "SMS",
                                        null,
                                        "Cito: {reference}",
                                        "ACTIVE",
                                        "",
                                        "")));
        when(templates.render(eq(key), eq("SMS"), anyMap()))
                .thenReturn(
                        new TemplateService.RenderedTemplate(key, "SMS", null, "Cito: event-ref"));
        when(preferences.resolveChannel(42, type))
                .thenReturn(
                        new MerchantNotificationPreferenceService.ResolvedNotification(
                                MerchantNotificationPreferenceService.Channel.NONE, null));
    }

    @Test
    void mandatoryPaymentBypassesOptionalChannelPreference() {
        event("payment.completed", "PENDING");
        when(jdbc.queryForList(contains("SELECT phone FROM merchant_admins"), anyMap()))
                .thenReturn(List.of(Map.of("phone", "+256700000001")));
        when(jdbc.queryForObject(
                        contains("SELECT id FROM communication_messages"),
                        any(MapSqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(99L);
        service.process(1);
        verify(jdbc)
                .update(
                        contains("INSERT INTO communication_outbox"),
                        any(MapSqlParameterSource.class));
        verify(channels, never()).isChannelEnabled(anyLong(), anyString());
    }

    @Test
    void optionalNotificationHonoursNonePreference() {
        event("payment.pending", "PENDING");
        service.process(1);
        verify(jdbc, never())
                .update(
                        contains("INSERT INTO communication_outbox"),
                        any(MapSqlParameterSource.class));
        verify(jdbc)
                .update(
                        contains("INSERT INTO notification_evidence"),
                        argThat(
                                (MapSqlParameterSource p) ->
                                        "PREFERENCE_SUPPRESSED".equals(p.getValue("outcome"))));
    }

    @Test
    void duplicateProcessedEventDoesNotQueueAgain() {
        event("payment.completed", "PROCESSED");
        service.process(1);
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void missingMandatoryRecipientLeavesVisibleEvidence() {
        event("payment.completed", "PENDING");
        service.process(1);
        verify(jdbc)
                .update(
                        contains("INSERT INTO notification_evidence"),
                        argThat(
                                (MapSqlParameterSource p) ->
                                        "NO_RECIPIENT".equals(p.getValue("outcome"))));
    }

    @Test
    void quietHoursCrossMidnightAndKeepTimezone() {
        Instant evening = Instant.parse("2026-09-10T20:00:00Z");
        assertThat(
                        NotificationOrchestrator.afterQuietHours(
                                evening, "22:00", "07:00", "Africa/Kampala"))
                .isEqualTo(Instant.parse("2026-09-11T04:00:00Z"));
        Instant daytime = Instant.parse("2026-09-10T10:00:00Z");
        assertThat(
                        NotificationOrchestrator.afterQuietHours(
                                daytime, "22:00", "07:00", "Africa/Kampala"))
                .isEqualTo(daytime);
    }

    @Test
    void catalogueIncludesAllAlertGroupsAndSecurityClassification() {
        assertThat(NotificationEventCatalog.all())
                .extracting(NotificationEventCatalog.Definition::group)
                .contains("PLATFORM_OPERATIONS", "FINANCE", "SECURITY", "COMPLIANCE");
        assertThat(NotificationEventCatalog.require("security.password.changed").classification())
                .isEqualTo(NotificationEventCatalog.Classification.MANDATORY);
        assertThatThrownBy(() -> NotificationEventCatalog.require("invented.event"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
