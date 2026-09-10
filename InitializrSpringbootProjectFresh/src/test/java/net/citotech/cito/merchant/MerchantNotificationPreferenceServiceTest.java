package net.citotech.cito.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.citotech.cito.gateway.PaymentGatewayException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Covers audit N5: merchants can set a per-event notification channel/address instead of always
 * being emailed at their primary contact with no opt-out - unconfigured events keep the previous
 * implicit EMAIL-to-primary-contact behavior, an explicit NONE suppresses the send, only catalog
 * event types are accepted, and repeat saves for the same event upsert rather than duplicate.
 */
@SuppressWarnings({"unchecked"})
class MerchantNotificationPreferenceServiceTest {

    @Test
    void smsWithoutAnExplicitAddressUsesPhoneInsteadOfEmail() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("channel", "SMS");
        row.put("notify_address", null);
        when(jdbc.queryForList(
                        contains("merchant_notification_preferences"),
                        any(MapSqlParameterSource.class)))
                .thenReturn(List.of(row));
        when(jdbc.queryForObject(
                        contains("SELECT phone FROM merchant_admins"),
                        any(MapSqlParameterSource.class),
                        eq(String.class)))
                .thenReturn("+256700000001");
        var result =
                new MerchantNotificationPreferenceService(jdbc)
                        .resolveChannel(42, "payment.completed");
        assertThat(result.channel()).isEqualTo(MerchantNotificationPreferenceService.Channel.SMS);
        assertThat(result.address()).isEqualTo("+256700000001");
        assertThat(result.shouldSend()).isTrue();
    }

    @Test
    void resolveChannelDefaultsToEmailAtThePrimaryContactWhenNoPreferenceIsConfigured() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForList(
                        contains("merchant_notification_preferences"),
                        any(MapSqlParameterSource.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForObject(
                        contains("merchant_admins"),
                        any(MapSqlParameterSource.class),
                        eq(String.class)))
                .thenReturn("merchant-primary@example.com");
        MerchantNotificationPreferenceService service =
                new MerchantNotificationPreferenceService(jdbcTemplate);

        MerchantNotificationPreferenceService.ResolvedNotification notification =
                service.resolveChannel(42L, "payment.completed");

        assertThat(notification.channel())
                .isEqualTo(MerchantNotificationPreferenceService.Channel.EMAIL);
        assertThat(notification.address()).isEqualTo("merchant-primary@example.com");
        assertThat(notification.shouldSend()).isTrue();
    }

    @Test
    void listDefaultsEveryUnconfiguredCatalogEventToEmailAtThePrimaryContact() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForList(
                        contains("merchant_notification_preferences"),
                        any(MapSqlParameterSource.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForObject(
                        contains("merchant_admins"),
                        any(MapSqlParameterSource.class),
                        eq(String.class)))
                .thenReturn("merchant-primary@example.com");
        MerchantNotificationPreferenceService service =
                new MerchantNotificationPreferenceService(jdbcTemplate);

        List<MerchantNotificationPreferenceService.Preference> preferences = service.list(42L);

        assertThat(preferences).isNotEmpty();
        assertThat(preferences)
                .allSatisfy(
                        p -> {
                            assertThat(p.explicit()).isFalse();
                            assertThat(p.channel())
                                    .isEqualTo(MerchantNotificationPreferenceService.Channel.EMAIL);
                            assertThat(p.notifyAddress()).isEqualTo("merchant-primary@example.com");
                        });
        assertThat(preferences)
                .extracting(MerchantNotificationPreferenceService.Preference::eventType)
                .contains(
                        "payment.completed",
                        "payout.completed",
                        "payout.failed",
                        "refund.completed");
    }

    @Test
    void anExplicitNonePreferenceSuppressesSending() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("channel", "NONE");
        row.put("notify_address", null);
        when(jdbcTemplate.queryForList(
                        contains("merchant_notification_preferences"),
                        any(MapSqlParameterSource.class)))
                .thenReturn(List.of(row));
        MerchantNotificationPreferenceService service =
                new MerchantNotificationPreferenceService(jdbcTemplate);

        MerchantNotificationPreferenceService.ResolvedNotification notification =
                service.resolveChannel(42L, "payout.failed");

        assertThat(notification.channel())
                .isEqualTo(MerchantNotificationPreferenceService.Channel.NONE);
        assertThat(notification.shouldSend()).isFalse();
    }

    @Test
    void saveRejectsAnEventTypeNotInTheWebhookCatalog() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        MerchantNotificationPreferenceService service =
                new MerchantNotificationPreferenceService(jdbcTemplate);

        assertThatThrownBy(() -> service.save(42L, "not.a.real.event", "EMAIL", null))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("Unknown notification event type");
    }

    @Test
    void saveRejectsAnUnknownChannel() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        MerchantNotificationPreferenceService service =
                new MerchantNotificationPreferenceService(jdbcTemplate);

        assertThatThrownBy(() -> service.save(42L, "payment.completed", "CARRIER_PIGEON", null))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("Unknown notification channel");
    }

    @Test
    void saveUpsertsOnTheMerchantAndEventTypeUniqueKey() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        MerchantNotificationPreferenceService service =
                new MerchantNotificationPreferenceService(jdbcTemplate);

        MerchantNotificationPreferenceService.Preference saved =
                service.save(42L, "PAYOUT.COMPLETED", "sms", "+256770000000");

        assertThat(saved.eventType()).isEqualTo("payout.completed");
        assertThat(saved.channel()).isEqualTo(MerchantNotificationPreferenceService.Channel.SMS);
        assertThat(saved.notifyAddress()).isEqualTo("+256770000000");
        verify(jdbcTemplate)
                .update(
                        argThat(
                                sql ->
                                        sql.contains("ON DUPLICATE KEY UPDATE")
                                                && sql.contains(
                                                        "INSERT INTO merchant_notification_preferences")),
                        any(MapSqlParameterSource.class));
    }

    @Test
    void resolveChannelFallsBackToEmailWhenThePrimaryContactLookupFindsNoAdmin() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForList(
                        contains("merchant_notification_preferences"),
                        any(MapSqlParameterSource.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForObject(
                        contains("merchant_admins"),
                        any(MapSqlParameterSource.class),
                        eq(String.class)))
                .thenThrow(new EmptyResultDataAccessException(1));
        MerchantNotificationPreferenceService service =
                new MerchantNotificationPreferenceService(jdbcTemplate);

        MerchantNotificationPreferenceService.ResolvedNotification notification =
                service.resolveChannel(42L, "refund.completed");

        assertThat(notification.channel())
                .isEqualTo(MerchantNotificationPreferenceService.Channel.EMAIL);
        assertThat(notification.address()).isNull();
        assertThat(notification.shouldSend()).isFalse();
    }
}
