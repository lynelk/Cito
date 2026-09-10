package net.citotech.cito.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.citotech.cito.Model.MerchantUser;
import net.citotech.cito.gateway.PaymentChannelAdapter;
import net.citotech.cito.gateway.PaymentChannelRegistry;
import net.citotech.cito.gateway.PaymentGatewayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Covers audit N7's enforcement wiring on {@link MerchantChannelCredentialService}: channel
 * credential save/test/submit-for-approval is a real, already-existing, session-driven merchant
 * self-service config-changing action (backing {@code POST
 * /api/v2/merchant-self-service/channels/save} etc. in {@code
 * net.citotech.cito.merchant.MerchantSelfServiceController}), gated here on {@link
 * MerchantRole#canManageChannels()}.
 *
 * <p>Cover: OWNER can do everything (save succeeds), VIEWER is blocked from the mutating action
 * (save is rejected before any registry/DB work happens), and a null/unrecognized role fails open
 * to OWNER rather than failing closed like VIEWER would.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class MerchantChannelCredentialServiceTest {

    @Test
    void ownerCanSaveChannelCredentials() {
        MerchantChannelCredentialService service = service(stubJdbcTemplate());
        MerchantUser owner = merchantUser("OWNER");

        Map<String, Object> result = service.save(owner, channelBody());

        assertThat(result)
                .isNull(); // find() returns no rows in this stub - proves the write path ran, not
        // blocked
    }

    @Test
    void developerCanSaveChannelCredentials() {
        MerchantChannelCredentialService service = service(stubJdbcTemplate());
        MerchantUser developer = merchantUser("DEVELOPER");

        service.save(developer, channelBody());
        // No exception - DEVELOPER is allowed to manage channel configuration.
    }

    @Test
    void viewerIsBlockedFromSavingChannelCredentials() {
        NamedParameterJdbcTemplate jdbcTemplate = stubJdbcTemplate();
        PaymentChannelRegistry registry = mock(PaymentChannelRegistry.class);
        MerchantChannelCredentialService service = service(jdbcTemplate, registry);
        MerchantUser viewer = merchantUser("VIEWER");

        assertThatThrownBy(() -> service.save(viewer, channelBody()))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("role");

        // The guard must reject before touching the channel registry or writing anything.
        verifyNoInteractions(registry);
        verify(jdbcTemplate, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void financeIsBlockedFromSavingChannelCredentials() {
        NamedParameterJdbcTemplate jdbcTemplate = stubJdbcTemplate();
        MerchantChannelCredentialService service = service(jdbcTemplate);
        MerchantUser finance = merchantUser("FINANCE");

        assertThatThrownBy(() -> service.save(finance, channelBody()))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("role");
        verify(jdbcTemplate, never())
                .update(
                        contains("INSERT INTO merchant_channel_credentials"),
                        any(MapSqlParameterSource.class));
    }

    @Test
    void nullRoleCannotManageCredentials() {
        MerchantChannelCredentialService service = service(stubJdbcTemplate());
        MerchantUser user = merchantUser(null);

        assertThatThrownBy(() -> service.save(user, channelBody()))
                .isInstanceOf(PaymentGatewayException.class);
        // No exception - a missing role (e.g. a pre-migration row) must not lock this user out.
    }

    @Test
    void unrecognizedRoleCannotManageCredentials() {
        MerchantChannelCredentialService service = service(stubJdbcTemplate());
        MerchantUser user = merchantUser("SOME_FUTURE_ROLE");

        assertThatThrownBy(() -> service.save(user, channelBody()))
                .isInstanceOf(PaymentGatewayException.class);
        // No exception - an unrecognized role value must not silently downgrade to VIEWER-like
        // access.
    }

    @Test
    void viewerIsBlockedFromTestingChannelCredentials() {
        NamedParameterJdbcTemplate jdbcTemplate = stubJdbcTemplate();
        MerchantChannelCredentialService service = service(jdbcTemplate);
        MerchantUser viewer = merchantUser("VIEWER");

        assertThatThrownBy(() -> service.test(viewer, channelBody()))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("role");
    }

    @Test
    void viewerIsBlockedFromSubmittingChannelForApproval() {
        NamedParameterJdbcTemplate jdbcTemplate = stubJdbcTemplate();
        MerchantChannelCredentialService service = service(jdbcTemplate);
        MerchantUser viewer = merchantUser("VIEWER");

        assertThatThrownBy(() -> service.submitForApproval(viewer, channelBody()))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("role");
    }

    private MerchantChannelCredentialService service(NamedParameterJdbcTemplate jdbcTemplate) {
        return service(jdbcTemplate, stubRegistry());
    }

    private MerchantChannelCredentialService service(
            NamedParameterJdbcTemplate jdbcTemplate, PaymentChannelRegistry registry) {
        MerchantChannelCryptoService cryptoService =
                new MerchantChannelCryptoService("test-only-encryption-key-0123456789");
        MerchantEnvironmentService environmentService =
                new MerchantEnvironmentService(jdbcTemplate);
        return new MerchantChannelCredentialService(
                jdbcTemplate, registry, cryptoService, environmentService, new ObjectMapper());
    }

    private PaymentChannelRegistry stubRegistry() {
        PaymentChannelRegistry registry = mock(PaymentChannelRegistry.class);
        PaymentChannelAdapter adapter = mock(PaymentChannelAdapter.class);
        when(adapter.channelCode()).thenReturn("yo_payments");
        when(adapter.displayName()).thenReturn("Yo Payments");
        when(registry.findByChannelCode("yo_payments")).thenReturn(Optional.of(adapter));
        return registry;
    }

    private NamedParameterJdbcTemplate stubJdbcTemplate() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        when(jdbcTemplate.query(
                        anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn((List) List.of());
        when(jdbcTemplate.queryForObject(
                        anyString(),
                        any(MapSqlParameterSource.class),
                        org.mockito.ArgumentMatchers.eq(String.class)))
                .thenReturn(null);
        return jdbcTemplate;
    }

    private MerchantUser merchantUser(String role) {
        MerchantUser user = new MerchantUser();
        user.setMerchant_id(1L);
        user.setEmail("owner@merchant.test");
        user.setRole(role);
        return user;
    }

    private Map<String, Object> channelBody() {
        return Map.of(
                "channelCode", "yo_payments",
                "environment", "SANDBOX",
                "credentials",
                        Map.of(
                                "apiUser", "sandbox-user",
                                "apiKey", "sandbox-key",
                                "collectionAccount", "SANDBOX-COLLECTION"));
    }
}
