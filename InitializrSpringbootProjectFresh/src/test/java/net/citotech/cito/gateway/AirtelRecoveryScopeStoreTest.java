package net.citotech.cito.gateway;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class AirtelRecoveryScopeStoreTest {
    @Test
    void providerIdentityNormalizesOnlyEquivalentEndpointForms() {
        assertThat(identity("LEGACY_MERCHANT", "https://openapi.airtel.africa/", "app-one", "UGX"))
                .isEqualTo(
                        identity(
                                "LEGACY_MERCHANT",
                                "https://openapi.airtel.africa:443",
                                "app-one",
                                "UGX"));
    }

    @Test
    void ownerApplicationCurrencyAndEnvironmentRemainIsolated() {
        String expected =
                identity("LEGACY_MERCHANT", "https://openapi.airtel.africa", "app-one", "UGX");
        assertThat(expected)
                .isNotEqualTo(
                        identity(
                                "LEGACY_PLATFORM",
                                "https://openapi.airtel.africa",
                                "app-one",
                                "UGX"));
        assertThat(expected)
                .isNotEqualTo(
                        identity(
                                "LEGACY_MERCHANT",
                                "https://openapi.airtel.africa",
                                "app-two",
                                "UGX"));
        assertThat(expected)
                .isNotEqualTo(
                        identity(
                                "LEGACY_MERCHANT",
                                "https://openapiuat.airtel.africa",
                                "app-one",
                                "UGX"));
        assertThat(expected)
                .isNotEqualTo(
                        identity(
                                "LEGACY_MERCHANT",
                                "https://openapi.airtel.africa",
                                "app-one",
                                "USD"));
    }

    @Test
    void missingOriginalCredentialProvenanceNeverUsesCurrentSettingsAsEvidence() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> require(jdbc)).isInstanceOf(PaymentGatewayException.class);
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void changedCredentialOwnerFailsClosed() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn(List.of("0".repeat(64)));
        assertThatThrownBy(() -> require(jdbc)).isInstanceOf(PaymentGatewayException.class);
    }

    @Test
    void matchingOriginalProviderIdentityAllowsCurrentSecretRotation() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        // Secrets are deliberately absent from attribution: only the owning application is pinned.
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn(
                        List.of(
                                identity(
                                        "LEGACY_MERCHANT",
                                        "https://openapi.airtel.africa",
                                        "app-one",
                                        "UGX")));
        assertThatCode(() -> require(jdbc)).doesNotThrowAnyException();
    }

    @Test
    void replayCannotOverwriteAnEarlierDifferentIdentity() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenThrow(new DuplicateKeyException("existing"));
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn(List.of("0".repeat(64)));
        assertThatThrownBy(
                        () ->
                                AirtelRecoveryScopeStore.record(
                                        jdbc,
                                        "ref",
                                        42L,
                                        "COLLECT",
                                        "LEGACY_MERCHANT",
                                        "https://openapi.airtel.africa",
                                        "app-one",
                                        "UG",
                                        "UGX"))
                .isInstanceOf(PaymentGatewayException.class);
        verify(jdbc, never()).update(startsWith("UPDATE"), any(MapSqlParameterSource.class));
    }

    private static void require(NamedParameterJdbcTemplate jdbc) {
        AirtelRecoveryScopeStore.require(
                jdbc,
                "ref",
                42L,
                "COLLECT",
                "LEGACY_MERCHANT",
                "https://openapi.airtel.africa",
                "app-one",
                "UG",
                "UGX");
    }

    private static String identity(String source, String endpoint, String client, String currency) {
        return AirtelRecoveryScopeStore.fingerprint(source, endpoint, client, "UG", currency);
    }
}
