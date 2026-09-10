package net.citotech.cito.merchant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.citotech.cito.Common;
import net.citotech.cito.communication.notification.NotificationEventCatalog;
import net.citotech.cito.gateway.PaymentGatewayException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Self-service merchant notification preferences (audit N5): which channel (EMAIL/SMS/NONE) and
 * address a merchant wants used for each event type in {@link WebhookEventCatalog}, replacing the
 * previous implicit "always email the merchant's primary contact, no opt-out" behavior with an
 * explicit, per-event, per-merchant choice.
 *
 * <p>An event a merchant has never configured has no row in {@code
 * merchant_notification_preferences} and defaults to EMAIL at the merchant's primary contact (the
 * earliest-created {@code merchant_admins} row for that merchant) - existing merchants therefore
 * see no behavior change until they explicitly set a preference, including an explicit NONE to opt
 * out entirely. Event types are validated against {@link WebhookEventCatalog#isKnown(String)}
 * rather than a second, parallel list so the catalog stays the single source of truth for what a
 * preference can be set for.
 */
@Service
public class MerchantNotificationPreferenceService {

    /** Delivery channel for a notification. NONE suppresses the notification entirely. */
    public enum Channel {
        EMAIL,
        SMS,
        NONE
    }

    /** A single event's preference - explicit (a row a merchant chose) or defaulted. */
    public record Preference(
            String eventType, Channel channel, String notifyAddress, boolean explicit) {}

    /** What a send-site should do for one merchant+event: the channel, and the address to use. */
    public record ResolvedNotification(Channel channel, String address) {
        public boolean shouldSend() {
            return channel != Channel.NONE && address != null && !address.trim().isEmpty();
        }
    }

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public MerchantNotificationPreferenceService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Lists one {@link Preference} per event in the catalog: the merchant's explicit choice where
     * one exists, otherwise a defaulted EMAIL-to-primary-contact entry marked {@code
     * explicit=false} so a UI can show the merchant which rows are actually configured versus
     * inherited defaults.
     */
    public List<Preference> list(long merchantId) {
        Map<String, Preference> configured = new LinkedHashMap<>();
        for (Map<String, Object> row : rawRows(merchantId)) {
            String eventType = String.valueOf(row.get("event_type"));
            Channel channel = Channel.valueOf(String.valueOf(row.get("channel")));
            String address =
                    row.get("notify_address") == null
                            ? null
                            : String.valueOf(row.get("notify_address"));
            configured.put(eventType, new Preference(eventType, channel, address, true));
        }
        String defaultAddress = primaryMerchantEmail(merchantId);
        List<Preference> result = new ArrayList<>();
        for (NotificationEventCatalog.Definition definition : NotificationEventCatalog.all()) {
            Preference existing = configured.get(definition.type());
            result.add(
                    existing != null
                            ? existing
                            : new Preference(
                                    definition.type(), Channel.EMAIL, defaultAddress, false));
        }
        return result;
    }

    /**
     * Upserts a merchant's preference for a single event type (unique per merchant_id+event_type -
     * a second save for the same event replaces the first). Rejects any event type not present in
     * {@link WebhookEventCatalog} with a clear error rather than silently accepting a typo that
     * would then never match a real notification lookup.
     */
    public Preference save(
            long merchantId, String eventType, String channel, String notifyAddress) {
        if (!NotificationEventCatalog.isKnown(eventType)) {
            throw new PaymentGatewayException(
                    "Unknown notification event type: "
                            + eventType
                            + ". See GET /api/v2/webhooks/events for the catalog.");
        }
        Channel parsedChannel = parseChannel(channel);
        String normalizedAddress = isBlank(notifyAddress) ? null : notifyAddress.trim();
        String normalizedEventType = normalize(eventType);

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("merchant_id", merchantId);
        p.addValue("event_type", normalizedEventType);
        p.addValue("channel", parsedChannel.name());
        p.addValue("notify_address", normalizedAddress);
        jdbcTemplate.update(
                "INSERT INTO merchant_notification_preferences (merchant_id, event_type, channel, notify_address) "
                        + "VALUES (:merchant_id, :event_type, :channel, :notify_address) "
                        + "ON DUPLICATE KEY UPDATE channel=:channel, notify_address=:notify_address, updated_at=CURRENT_TIMESTAMP",
                p);

        String effectiveAddress =
                normalizedAddress != null
                        ? normalizedAddress
                        : primaryMerchantAddress(merchantId, parsedChannel);
        return new Preference(normalizedEventType, parsedChannel, effectiveAddress, true);
    }

    /**
     * Resolves what a caller about to send a notification for {@code eventType} should do for
     * {@code merchantId}: EMAIL/SMS with a concrete address, or NONE (see {@link
     * ResolvedNotification#shouldSend()}) to suppress the send entirely. An event type the caller
     * passes that isn't in the catalog resolves to the same EMAIL-to-primary-contact default rather
     * than throwing - a notification-routing lookup should never fail an otherwise-successful
     * payment/payout/refund.
     */
    public ResolvedNotification resolveChannel(long merchantId, String eventType) {
        if (!NotificationEventCatalog.isKnown(eventType)) {
            return new ResolvedNotification(Channel.EMAIL, primaryMerchantEmail(merchantId));
        }
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("merchant_id", merchantId);
        p.addValue("event_type", normalize(eventType));
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT channel, notify_address FROM merchant_notification_preferences "
                                + "WHERE merchant_id=:merchant_id AND event_type=:event_type LIMIT 1",
                        p);
        if (rows.isEmpty()) {
            return new ResolvedNotification(Channel.EMAIL, primaryMerchantEmail(merchantId));
        }
        Map<String, Object> row = rows.get(0);
        Channel channel = Channel.valueOf(String.valueOf(row.get("channel")));
        if (channel == Channel.NONE) {
            return new ResolvedNotification(Channel.NONE, null);
        }
        String address =
                row.get("notify_address") == null
                        ? null
                        : String.valueOf(row.get("notify_address"));
        String resolvedAddress =
                isBlank(address) ? primaryMerchantAddress(merchantId, channel) : address.trim();
        return new ResolvedNotification(channel, resolvedAddress);
    }

    private List<Map<String, Object>> rawRows(long merchantId) {
        return jdbcTemplate.queryForList(
                "SELECT event_type, channel, notify_address FROM merchant_notification_preferences WHERE merchant_id=:merchant_id",
                new MapSqlParameterSource("merchant_id", merchantId));
    }

    /**
     * The merchant's earliest-created admin contact email - the same "primary contact" notion used
     * elsewhere in this codebase for merchant account/credential emails.
     */
    private String primaryMerchantEmail(long merchantId) {
        return primaryMerchantAddress(merchantId, Channel.EMAIL);
    }

    private String primaryMerchantAddress(long merchantId, Channel channel) {
        if (channel == Channel.NONE) return null;
        try {
            String sql =
                    "SELECT "
                            + (channel == Channel.SMS ? "phone" : "email")
                            + " FROM "
                            + Common.DB_TABLE_MERCHANT_USERS
                            + " WHERE merchant_id=:merchant_id ORDER BY id ASC LIMIT 1";
            String value =
                    jdbcTemplate.queryForObject(
                            sql,
                            new MapSqlParameterSource("merchant_id", merchantId),
                            String.class);
            return isBlank(value) ? null : value.trim();
        } catch (DataAccessException e) {
            return null;
        }
    }

    private Channel parseChannel(String channel) {
        if (isBlank(channel)) {
            throw new PaymentGatewayException("channel is required (EMAIL, SMS, or NONE)");
        }
        try {
            return Channel.valueOf(channel.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new PaymentGatewayException(
                    "Unknown notification channel: " + channel + ". Use EMAIL, SMS, or NONE.");
        }
    }

    private String normalize(String eventType) {
        return eventType.trim().toLowerCase();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
