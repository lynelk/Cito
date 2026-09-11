package net.citotech.cito.merchant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import net.citotech.cito.gateway.PaymentChannelRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.*;

class MerchantCredentialApprovalTest {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    MerchantEnvironmentService environments = mock(MerchantEnvironmentService.class);
    MerchantChannelCredentialService service =
            new MerchantChannelCredentialService(
                    jdbc,
                    mock(PaymentChannelRegistry.class),
                    new MerchantChannelCryptoService("synthetic-test-key"),
                    environments,
                    new ObjectMapper());

    Map<String, Object> row() {
        return new HashMap<>(
                Map.of(
                        "id",
                        1L,
                        "merchant_id",
                        10L,
                        "revision",
                        7L,
                        "tested_revision",
                        7L,
                        "last_test_status",
                        "CONNECTIVITY_VERIFIED",
                        "status",
                        "SUBMITTED_FOR_APPROVAL",
                        "updated_by",
                        "maker@example.com",
                        "channel_code",
                        "mtn_momo",
                        "environment",
                        "PRODUCTION"));
    }

    void stub(Map<String, Object> row) {
        when(jdbc.queryForList(contains("FOR UPDATE"), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(row));
        when(environments.normalizedEnvironment(anyString())).thenAnswer(c -> c.getArgument(0));
    }

    @Test
    void makerCannotApproveTheirOwnRevision() {
        stub(row());
        assertThatThrownBy(() -> service.decide(1, 7, "ACTIVE", "maker@example.com", "reviewed"))
                .hasMessageContaining("Requester");
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void staleRevisionCannotBeApproved() {
        stub(row());
        assertThatThrownBy(() -> service.decide(1, 6, "ACTIVE", "checker@example.com", "reviewed"))
                .hasMessageContaining("revision changed");
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void structureCheckIsInsufficientForActivation() {
        var row = row();
        row.put("last_test_status", "STRUCTURE_VALID");
        stub(row);
        assertThatThrownBy(() -> service.decide(1, 7, "ACTIVE", "checker@example.com", "reviewed"))
                .hasMessageContaining("connectivity");
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void independentCheckerCanApproveTheVerifiedCurrentRevision() {
        stub(row());
        service.decide(1, 7, "ACTIVE", "checker@example.com", "Provider authentication verified");
        verify(jdbc)
                .update(
                        contains("UPDATE merchant_channel_credentials SET status"),
                        argThat(
                                (MapSqlParameterSource p) ->
                                        "ACTIVE".equals(p.getValue("status"))
                                                && "checker@example.com"
                                                        .equals(p.getValue("actor"))));
        verify(jdbc)
                .update(
                        contains("INSERT INTO merchant_channel_audit_events"),
                        any(MapSqlParameterSource.class));
    }
}
