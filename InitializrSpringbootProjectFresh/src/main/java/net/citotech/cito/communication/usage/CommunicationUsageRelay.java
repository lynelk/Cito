package net.citotech.cito.communication.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.citotech.cito.communication.delivery.DeliveryLogRepository;
import net.citotech.cito.communication.delivery.MessageDelivery;
import net.citotech.cito.platform.kernel.PlatformUsageContract;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Per-channel usage relay: converts sent communication deliveries into the common Cito usage
 * contract, then marks the delivery row billed. Payments, communications, identity and vending
 * therefore converge on one Billing/BaaS metering boundary rather than owning separate meters.
 *
 * <p>The idempotency key ({@code comm:<channel>:<deliveryId>}) remains stable across retries. A row
 * that cannot be metered stays unbilled and is retried; an outage never silently drops billable
 * usage.
 */
@Component
@ConditionalOnProperty(
        value = "cpay.communication.usage.relay.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class CommunicationUsageRelay {

    private static final Logger logger = Logger.getLogger(CommunicationUsageRelay.class.getName());
    private static final int DEFAULT_BATCH_LIMIT = 100;
    private static final String IDEMPOTENCY_PREFIX = "comm:";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final DeliveryLogRepository deliveryLogRepository;
    private final PlatformUsageContract usage;

    public CommunicationUsageRelay(
            NamedParameterJdbcTemplate jdbcTemplate,
            DeliveryLogRepository deliveryLogRepository,
            PlatformUsageContract usage) {
        this.jdbcTemplate = jdbcTemplate;
        this.deliveryLogRepository = deliveryLogRepository;
        this.usage = usage;
    }

    @Scheduled(fixedDelayString = "${cpay.communication.usage.relay.fixed-delay-ms:30000}")
    @SchedulerLock(
            name = "communicationUsageRelay",
            lockAtMostFor = "PT5M",
            lockAtLeastFor = "PT10S")
    public void relayDue() {
        try {
            int relayed = relayDue(DEFAULT_BATCH_LIMIT);
            if (relayed > 0) {
                logger.log(
                        Level.INFO, "Communication usage relay processed {0} delivery(s)", relayed);
            }
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Communication usage relay failed: " + ex.getMessage(), ex);
        }
    }

    int relayDue(int limit) {
        int total = 0;
        for (String channel : registeredChannels()) {
            total += relayChannel(channel, limit);
        }
        return total;
    }

    private int relayChannel(String channel, int limit) {
        int relayed = 0;
        long watermark = watermarkFor(channel);
        while (true) {
            long before = watermark;
            List<MessageDelivery> batch = deliveryLogRepository.sentSince(channel, before, limit);
            if (batch.isEmpty()) break;
            for (MessageDelivery delivery : batch) {
                if (relayOne(delivery)) {
                    relayed++;
                    watermark = delivery.id();
                } else {
                    saveWatermark(channel, watermark);
                    return relayed;
                }
            }
            if (watermark == before) break;
        }
        saveWatermark(channel, watermark);
        return relayed;
    }

    private boolean relayOne(MessageDelivery delivery) {
        try {
            if (delivery.merchantId() == 0) {
                deliveryLogRepository.markBilled(delivery.id());
                return true;
            }
            Map<String, String> dimensions = new HashMap<>();
            dimensions.put("channel", delivery.channel());
            if (delivery.providerCode() != null && !delivery.providerCode().isBlank()) {
                dimensions.put("provider_code", delivery.providerCode());
            }
            usage.recordUsage(
                    delivery.merchantId(),
                    serviceCodeFor(delivery.channel()),
                    meterCodeFor(delivery.channel()),
                    Instant.now(),
                    unitsFor(delivery),
                    null,
                    dimensions,
                    "COMM_DELIVERY:" + delivery.id(),
                    IDEMPOTENCY_PREFIX + delivery.channel() + ":" + delivery.id());
            deliveryLogRepository.markBilled(delivery.id());
            return true;
        } catch (Exception ex) {
            logger.log(
                    Level.WARNING,
                    "Usage relay failed for delivery " + delivery.id() + ": " + ex.getMessage(),
                    ex);
            return false;
        }
    }

    private BigDecimal unitsFor(MessageDelivery delivery) {
        if (!"SMS".equals(delivery.channel())) return BigDecimal.ONE;
        List<BigDecimal> quantities =
                jdbcTemplate.queryForList(
                        "SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(m.metadata_json,'$.segments')) AS DECIMAL(18,4))"
                                + " FROM communication_message_deliveries d JOIN communication_messages m ON m.id=d.communication_id"
                                + " WHERE d.id=:id AND m.merchant_id=:merchant",
                        new MapSqlParameterSource()
                                .addValue("id", delivery.id())
                                .addValue("merchant", delivery.merchantId()),
                        BigDecimal.class);
        if (quantities.isEmpty()) return BigDecimal.ONE;
        BigDecimal quantity = quantities.getFirst();
        if (quantity == null || quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 0) {
            throw new IllegalStateException("SMS segment evidence is invalid");
        }
        return quantity;
    }

    private List<String> registeredChannels() {
        return jdbcTemplate.query(
                "SELECT channel FROM communication_usage_watermark ORDER BY channel ASC",
                new MapSqlParameterSource(),
                (rs, rowNum) -> rs.getString("channel"));
    }

    private long watermarkFor(String channel) {
        List<Long> rows =
                jdbcTemplate.query(
                        "SELECT last_delivery_id FROM communication_usage_watermark WHERE channel=:channel",
                        new MapSqlParameterSource("channel", channel),
                        (rs, rowNum) -> rs.getLong("last_delivery_id"));
        return rows.isEmpty() ? 0L : rows.getFirst();
    }

    private void saveWatermark(String channel, long lastDeliveryId) {
        jdbcTemplate.update(
                "UPDATE communication_usage_watermark SET last_delivery_id=:last_delivery_id,"
                        + " processed_flag='Y' WHERE channel=:channel",
                new MapSqlParameterSource()
                        .addValue("channel", channel)
                        .addValue("last_delivery_id", lastDeliveryId));
    }

    private String serviceCodeFor(String channel) {
        return switch (channel) {
            case "EMAIL" -> "EMAIL";
            case "WHATSAPP" -> "WHATSAPP";
            case "USSD" -> "USSD";
            default -> "SMS";
        };
    }

    private String meterCodeFor(String channel) {
        return switch (channel) {
            case "EMAIL" -> "email_delivered_count";
            case "WHATSAPP" -> "whatsapp_message_count";
            case "USSD" -> "ussd_session_count";
            default -> "sms_sent_count";
        };
    }
}
