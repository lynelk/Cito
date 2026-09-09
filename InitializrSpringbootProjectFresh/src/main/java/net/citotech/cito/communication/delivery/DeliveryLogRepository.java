package net.citotech.cito.communication.delivery;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Read/write access to the canonical communication delivery ledger. */
@Repository
public class DeliveryLogRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public DeliveryLogRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(
            long merchantId,
            String channel,
            String providerCode,
            String referenceType,
            Long referenceId,
            String recipient) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("merchant_id", merchantId);
        p.addValue("channel", channel);
        p.addValue("provider_code", providerCode);
        p.addValue("reference_type", referenceType);
        p.addValue("reference_id", referenceId);
        p.addValue("recipient", recipient);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(
                "INSERT INTO communication_message_deliveries"
                        + " (merchant_id, channel, provider_code, reference_type, reference_id, recipient,"
                        + " status) VALUES (:merchant_id, :channel, :provider_code, :reference_type,"
                        + " :reference_id, :recipient, 'PENDING')",
                p,
                keyHolder,
                new String[] {"id"});
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("Inserted delivery row without a generated id");
        return key.longValue();
    }

    public int updateStatus(long id, DeliveryStatus status, String trace, String gwResponse) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", id);
        p.addValue("status", status.name());
        p.addValue("trace", trace);
        p.addValue("gw_response", gwResponse);
        return jdbcTemplate.update(
                "UPDATE communication_message_deliveries SET status=:status, trace=:trace,"
                        + " gw_response=:gw_response WHERE id=:id",
                p);
    }

    /** Saves the provider callback correlation ID returned by a provider send. */
    public int updateProviderMessageId(long id, String providerMessageId) {
        if (providerMessageId == null || providerMessageId.isBlank()) return 0;
        return jdbcTemplate.update(
                "UPDATE communication_message_deliveries SET provider_message_id=:provider_message_id"
                        + " WHERE id=:id",
                new MapSqlParameterSource()
                        .addValue("provider_message_id", providerMessageId.trim())
                        .addValue("id", id));
    }

    /** Applies a normalized provider delivery receipt idempotently. */
    public int updateByProviderMessageId(
            String providerCode,
            String providerMessageId,
            DeliveryStatus status,
            String trace,
            String safeResponse) {
        if (providerCode == null || providerCode.isBlank()
                || providerMessageId == null || providerMessageId.isBlank()) return 0;
        return jdbcTemplate.update(
                "UPDATE communication_message_deliveries SET status=:status, trace=:trace,"
                        + " gw_response=:response, delivered_at=IF(:delivered, NOW(), delivered_at)"
                        + " WHERE provider_code=:provider AND provider_message_id=:provider_message_id",
                new MapSqlParameterSource()
                        .addValue("status", status.name())
                        .addValue("trace", trace)
                        .addValue("response", safeResponse)
                        .addValue("delivered", status == DeliveryStatus.DELIVERED)
                        .addValue("provider", providerCode.trim().toUpperCase())
                        .addValue("provider_message_id", providerMessageId.trim()));
    }

    public int markBilled(long id) {
        return jdbcTemplate.update(
                "UPDATE communication_message_deliveries SET billed_flag='Y'"
                        + " WHERE id=:id AND billed_flag='N'",
                new MapSqlParameterSource("id", id));
    }

    public Optional<MessageDelivery> findById(long id) {
        List<MessageDelivery> rows =
                jdbcTemplate.query(
                        "SELECT id, merchant_id, channel, provider_code, reference_type, reference_id,"
                                + " recipient, status, trace, gw_response, charged_amount, billed_flag"
                                + " FROM communication_message_deliveries WHERE id=:id",
                        new MapSqlParameterSource("id", id),
                        this::mapRow);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<MessageDelivery> sentSince(String channel, long afterId, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("channel", channel);
        p.addValue("after_id", afterId);
        p.addValue("limit", Math.max(1, Math.min(limit, 500)));
        return jdbcTemplate.query(
                "SELECT id, merchant_id, channel, provider_code, reference_type, reference_id,"
                        + " recipient, status, trace, gw_response, charged_amount, billed_flag"
                        + " FROM communication_message_deliveries"
                        + " WHERE channel=:channel AND id>:after_id AND status='SENT' AND billed_flag='N'"
                        + " ORDER BY id ASC LIMIT :limit",
                p,
                this::mapRow);
    }

    public List<MessageDelivery> listForMerchant(long merchantId, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("merchant_id", merchantId);
        p.addValue("limit", Math.max(1, Math.min(limit, 200)));
        return jdbcTemplate.query(
                "SELECT id, merchant_id, channel, provider_code, reference_type, reference_id,"
                        + " recipient, status, trace, gw_response, charged_amount, billed_flag"
                        + " FROM communication_message_deliveries WHERE merchant_id=:merchant_id"
                        + " ORDER BY id DESC LIMIT :limit",
                p,
                this::mapRow);
    }

    private MessageDelivery mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new MessageDelivery(
                rs.getLong("id"),
                rs.getLong("merchant_id"),
                rs.getString("channel"),
                rs.getString("provider_code"),
                rs.getString("reference_type"),
                (Long) rs.getObject("reference_id"),
                rs.getString("recipient"),
                DeliveryStatus.fromString(rs.getString("status")),
                rs.getString("trace"),
                rs.getString("gw_response"),
                rs.getBigDecimal("charged_amount") == null
                        ? BigDecimal.ZERO
                        : rs.getBigDecimal("charged_amount"),
                "Y".equals(rs.getString("billed_flag")));
    }
}
