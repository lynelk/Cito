package net.citotech.cito.sharedprovider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.gateway.ProviderCredentialProbeService;
import net.citotech.cito.gateway.ProviderCredentialProbeService.ProbeCheck;
import net.citotech.cito.merchant.MerchantChannelCredentialService;
import net.citotech.cito.merchant.MerchantChannelCryptoService;
import net.citotech.cito.merchant.MerchantEnvironmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class PlatformCredentialVerificationTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final ProviderCredentialProbeService probe = mock(ProviderCredentialProbeService.class);
    private SharedProviderAccessService service;

    @BeforeEach
    void setUp() {
        var crypto = new MerchantChannelCryptoService("test-only-crypto-key");
        service =
                new SharedProviderAccessService(
                        jdbc,
                        mock(MerchantChannelCredentialService.class),
                        crypto,
                        mock(MerchantEnvironmentService.class),
                        new ObjectMapper());
        ReflectionTestUtils.setField(service, "connectivity", probe);
        when(jdbc.queryForMap(
                        contains("SELECT * FROM platform_channel_credentials"),
                        any(MapSqlParameterSource.class)))
                .thenReturn(
                        Map.of(
                                "revision",
                                7L,
                                "channel_code",
                                "mtn_momo",
                                "environment",
                                "SANDBOX",
                                "country_code",
                                "UG",
                                "currency_code",
                                "EUR",
                                "credential_payload",
                                crypto.encrypt("{}")));
        when(jdbc.queryForList(
                        contains("credential_mask AS credentialMask"),
                        any(MapSqlParameterSource.class)))
                .thenReturn(List.of(Map.of("id", 1L, "revision", 7L, "credentialMask", "{}")));
    }

    @Test
    void partialFailureIsBoundToTestedRevisionAndReturnedWithoutActivation() {
        var checks =
                List.of(
                        new ProbeCheck("COLLECT", "VERIFIED", "Authenticated"),
                        new ProbeCheck("PAYOUT", "FAILED", "HTTP 401"));
        when(probe.probe(any(), any(), any(), any(), any())).thenReturn(checks);
        when(jdbc.update(
                        contains("SET last_test_status=:testStatus"),
                        any(MapSqlParameterSource.class)))
                .thenAnswer(
                        call -> {
                            MapSqlParameterSource values = call.getArgument(1);
                            assertThat(values.getValue("revision")).isEqualTo(7L);
                            assertThat(values.getValue("testStatus"))
                                    .isEqualTo("CONNECTIVITY_FAILED");
                            assertThat((String) call.getArgument(0))
                                    .doesNotContain("status='ACTIVE'");
                            return 1;
                        });
        assertThat(
                        service.verifyPlatformCredential(1L, "operator@example.com")
                                .get("verificationChecks"))
                .isEqualTo(checks);
        when(jdbc.queryForList(contains("FOR UPDATE"), any(MapSqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "channel_code",
                                        "mtn_momo",
                                        "revision",
                                        7L,
                                        "tested_revision",
                                        7L,
                                        "last_test_status",
                                        "CONNECTIVITY_FAILED",
                                        "status",
                                        "CONFIGURED",
                                        "updated_by",
                                        "maker@example.com")));
        assertThatThrownBy(() -> service.approvePlatformCredential(1L, "checker@example.com"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("Verify provider connectivity");
    }

    @Test
    void concurrentEditCannotReceiveVerificationEvidenceFromOlderSecrets() {
        when(probe.probe(any(), any(), any(), any(), any()))
                .thenReturn(
                        List.of(
                                new ProbeCheck("COLLECT", "VERIFIED", "Authenticated"),
                                new ProbeCheck("PAYOUT", "VERIFIED", "Authenticated")));
        when(jdbc.update(
                        contains("SET last_test_status=:testStatus"),
                        any(MapSqlParameterSource.class)))
                .thenReturn(0);
        assertThatThrownBy(() -> service.verifyPlatformCredential(1L, "operator@example.com"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("changed during verification");
    }
}
