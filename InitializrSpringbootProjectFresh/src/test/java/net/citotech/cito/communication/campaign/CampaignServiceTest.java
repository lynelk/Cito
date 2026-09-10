package net.citotech.cito.communication.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import net.citotech.cito.communication.campaign.CampaignService.CampaignRow;
import net.citotech.cito.communication.campaign.CampaignService.ItemRow;
import net.citotech.cito.communication.delivery.CommunicationDeliveryDispatcher;
import net.citotech.cito.communication.delivery.CommunicationDeliveryDispatcher.DeliveryOutcome;
import net.citotech.cito.communication.delivery.DeliveryStatus;
import net.citotech.cito.gateway.PaymentGatewayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Covers the V52 campaign service (track B4): validation of the create payload, staging one item
 * per recipient, queueing only a DRAFT campaign, the sweep dispatching PENDING items through the
 * delivery dispatcher and advancing progress, and per-item failure containment so one bad row
 * cannot abort the batch.
 */
class CampaignServiceTest {

    private NamedParameterJdbcTemplate jdbcTemplate;
    private CommunicationDeliveryDispatcher dispatcher;
    private CampaignService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        dispatcher = mock(CommunicationDeliveryDispatcher.class);
        when(dispatcher.dispatch(
                        any(Long.class),
                        anyString(),
                        anyString(),
                        nullable(String.class),
                        anyString(),
                        nullable(String.class),
                        any(Long.class)))
                .thenReturn(new DeliveryOutcome(900L, DeliveryStatus.SENT, "YO_SMS"));
        service = new CampaignService(jdbcTemplate, dispatcher);
    }

    @Test
    void createRejectsBlankName() {
        assertThatThrownBy(
                        () ->
                                service.create(
                                        7L, "  ", "SMS", "tpl", List.of("256700000001"), "admin"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("name is required");
    }

    @Test
    void createRejectsEmptyRecipients() {
        assertThatThrownBy(() -> service.create(7L, "Campaign", "SMS", "tpl", List.of(), "admin"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("at least one recipient is required");
    }

    @Test
    void createRejectsOverLimitRecipients() {
        List<String> tooMany = IntStream.range(0, 5001).mapToObj(i -> "2567" + i).toList();

        assertThatThrownBy(() -> service.create(7L, "Campaign", "SMS", "tpl", tooMany, "admin"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("5000 or fewer");
    }

    @Test
    void createStagesOneItemPerRecipient() {
        when(jdbcTemplate.queryForObject(
                        anyString(), any(MapSqlParameterSource.class), any(Class.class)))
                .thenReturn(42L);

        long campaignId =
                service.create(
                        7L,
                        "Receipt blast",
                        "SMS",
                        "merchant_sms_payment_receipt",
                        List.of("256700000001", "256700000002"),
                        "admin@cpay");

        assertThat(campaignId).isEqualTo(42L);
        // Two recipients -> exactly two item INSERTs (one per recipient).
        verify(jdbcTemplate, times(2))
                .update(
                        eq(
                                "INSERT INTO communication_campaign_items (campaign_id, recipient, status)"
                                        + " VALUES (:campaign_id, :recipient, 'PENDING')"),
                        any(MapSqlParameterSource.class));
    }

    @Test
    void queueOnlyFlippedDraftToQueued() {
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        int queued = service.queue(7L);

        assertThat(queued).isEqualTo(1);
        verify(jdbcTemplate).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void sweepDispatchesDueItemsThroughTheDispatcher() {
        when(jdbcTemplate.query(
                        anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(
                        invocation -> {
                            String sql = invocation.getArgument(0);
                            if (sql.contains("communication_campaign_items i JOIN")) {
                                return List.of(
                                        new ItemRow(11L, 5L, "256700000001", "Hello {amount}"));
                            }
                            if (sql.contains("FROM communication_campaigns WHERE id=:id")) {
                                return List.of(
                                        new CampaignRow(5L, 7L, "Blast", "SMS", "tpl", "QUEUED"));
                            }
                            return List.of();
                        });

        int processed = service.sweepDue(200);

        assertThat(processed).isEqualTo(1);
        verify(dispatcher).dispatch(7L, "SMS", "256700000001", null, "Hello {amount}", null, 11L);
    }

    @Test
    void sweepFailureIsContainedPerItem() {
        when(dispatcher.dispatch(
                        any(Long.class),
                        anyString(),
                        anyString(),
                        nullable(String.class),
                        anyString(),
                        nullable(String.class),
                        any(Long.class)))
                .thenThrow(new IllegalStateException("provider down"));
        when(jdbcTemplate.query(
                        anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(
                        invocation -> {
                            String sql = invocation.getArgument(0);
                            if (sql.contains("communication_campaign_items i JOIN")) {
                                return List.of(new ItemRow(11L, 5L, "256700000001", "Hello"));
                            }
                            if (sql.contains("FROM communication_campaigns WHERE id=:id")) {
                                return List.of(
                                        new CampaignRow(5L, 7L, "Blast", "SMS", "tpl", "QUEUED"));
                            }
                            return List.of();
                        });

        int processed = service.sweepDue(200);

        // The exception is swallowed per-row: the item is flipped to FAILED (contained) but the
        // sweep does not count it as processed — the next sweep will not re-pick it up either,
        // since PENDING was the filter.
        assertThat(processed).isZero();
        verify(jdbcTemplate)
                .update(
                        eq(
                                "UPDATE communication_campaign_items SET status='FAILED', trace=:trace"
                                        + " WHERE id=:id AND status='PENDING'"),
                        any(MapSqlParameterSource.class));
    }

    @Test
    void listBoundsTheLimit() {
        when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(Map.of("id", 1L)));

        List<Map<String, Object>> rows = service.list(7L, 10_000);

        assertThat(rows).hasSize(1);
    }
}
