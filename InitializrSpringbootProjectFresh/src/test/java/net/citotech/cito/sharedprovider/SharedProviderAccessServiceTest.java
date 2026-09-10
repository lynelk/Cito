package net.citotech.cito.sharedprovider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.merchant.MerchantChannelCredentialService;
import net.citotech.cito.merchant.MerchantChannelCryptoService;
import net.citotech.cito.merchant.MerchantEnvironmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class SharedProviderAccessServiceTest {
    private NamedParameterJdbcTemplate jdbc;
    private MerchantChannelCredentialService merchantCredentials;
    private MerchantEnvironmentService environments;
    private SharedProviderAccessService service;
    private Merchant merchant;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        merchantCredentials = mock(MerchantChannelCredentialService.class);
        environments = mock(MerchantEnvironmentService.class);
        when(environments.normalizedEnvironment(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service =
                new SharedProviderAccessService(
                        jdbc,
                        merchantCredentials,
                        new MerchantChannelCryptoService("shared-provider-test-key"),
                        environments,
                        new ObjectMapper());
        merchant = new Merchant();
        merchant.setId(42L);
        merchant.setAccount_number("M-42");
        merchant.setStatus("ACTIVE");
        when(jdbc.query(
                        contains("book_balance-reserved_balance"),
                        any(MapSqlParameterSource.class),
                        org.mockito.ArgumentMatchers
                                .<org.springframework.jdbc.core.RowMapper<BigDecimal>>any()))
                .thenReturn(List.of(new BigDecimal("1000")));
    }

    @Test
    void merchantApprovedCredentialsAlwaysWin() {
        when(merchantCredentials.loadDecrypted(merchant, "mtn_momo", "PRODUCTION"))
                .thenReturn(Map.of("credentialOwner", "merchant"));

        SharedProviderAccessService.CredentialContext context =
                service.resolve(
                        merchant,
                        "mtn_momo",
                        "PRODUCTION",
                        "UG",
                        "UGX",
                        "PAYOUT",
                        new BigDecimal("100.00"));

        assertEquals(SharedProviderAccessService.MERCHANT, context.source());
        assertEquals("merchant", context.credentials().get("credentialOwner"));
        assertFalse(context.shared());
        verify(jdbc, never())
                .queryForList(
                        contains("shared_provider_entitlements"), any(MapSqlParameterSource.class));
    }

    @Test
    void sharedFallbackRequiresActiveEntitlementAndPlatformCredential() {
        merchantCredentialsNotReady();
        when(jdbc.queryForList(
                        contains("FROM shared_provider_entitlements"),
                        any(MapSqlParameterSource.class)))
                .thenReturn(List.of(entitlement("500.00", "1000.00")));
        when(jdbc.queryForObject(
                        contains("COUNT(*) FROM platform_channel_credentials"),
                        any(MapSqlParameterSource.class),
                        eq(Integer.class)))
                .thenReturn(1);

        assertTrue(
                service.isReady(
                        merchant,
                        "mtn_momo",
                        "PRODUCTION",
                        "UG",
                        "UGX",
                        "PAYOUT",
                        new BigDecimal("100.00")));
    }

    @Test
    void sharedFallbackRejectsAmountAbovePerTransactionLimit() {
        merchantCredentialsNotReady();
        when(jdbc.queryForList(
                        contains("FROM shared_provider_entitlements"),
                        any(MapSqlParameterSource.class)))
                .thenReturn(List.of(entitlement("50.00", "1000.00")));

        assertFalse(
                service.isReady(
                        merchant,
                        "mtn_momo",
                        "PRODUCTION",
                        "UG",
                        "UGX",
                        "PAYOUT",
                        new BigDecimal("100.00")));
        verify(jdbc, never())
                .queryForObject(
                        contains("platform_channel_credentials"),
                        any(MapSqlParameterSource.class),
                        eq(Integer.class));
    }

    @Test
    void explicitSharedSourceDoesNotUseReadyMerchantCredentials() {
        when(jdbc.queryForList(
                        contains("FROM shared_provider_entitlements"),
                        any(MapSqlParameterSource.class)))
                .thenReturn(List.of(entitlement("500.00", "1000.00")));
        when(jdbc.queryForObject(
                        contains("COUNT(*) FROM platform_channel_credentials"),
                        any(MapSqlParameterSource.class),
                        eq(Integer.class)))
                .thenReturn(1);

        assertTrue(
                service.isReady(
                        merchant,
                        "mtn_momo",
                        "PRODUCTION",
                        "UG",
                        "UGX",
                        "COLLECT",
                        new BigDecimal("100.00"),
                        SharedProviderAccessService.PLATFORM_SHARED));
        verify(merchantCredentials, never()).ensureChannelReady(merchant, "mtn_momo", "PRODUCTION");
    }

    @Test
    void explicitMerchantSourceDoesNotFallBackToSharedCredentials() {
        merchantCredentialsNotReady();

        assertFalse(
                service.isReady(
                        merchant,
                        "mtn_momo",
                        "PRODUCTION",
                        "UG",
                        "UGX",
                        "COLLECT",
                        new BigDecimal("100.00"),
                        SharedProviderAccessService.MERCHANT));
        verify(jdbc, never())
                .queryForList(
                        contains("FROM shared_provider_entitlements"),
                        any(MapSqlParameterSource.class));
    }

    @Test
    void makerCannotApproveOwnEntitlement() {
        when(jdbc.queryForList(
                        contains("FROM shared_provider_entitlements WHERE id=:id FOR UPDATE"),
                        any(MapSqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id",
                                        7L,
                                        "status",
                                        "PENDING",
                                        "requested_by",
                                        "maker@example.com")));

        PaymentGatewayException error =
                assertThrows(
                        PaymentGatewayException.class,
                        () -> service.approveEntitlement(7L, "maker@example.com"));

        assertTrue(error.getMessage().contains("Maker-checker"));
    }

    private void merchantCredentialsNotReady() {
        doThrow(new PaymentGatewayException("not configured"))
                .when(merchantCredentials)
                .ensureChannelReady(merchant, "mtn_momo", "PRODUCTION");
    }

    private Map<String, Object> entitlement(String perTransaction, String daily) {
        return Map.of(
                "id",
                9L,
                "status",
                "ACTIVE",
                "per_transaction_limit",
                new BigDecimal(perTransaction),
                "daily_limit",
                new BigDecimal(daily),
                "requested_by",
                "maker@example.com",
                "used",
                BigDecimal.ZERO);
    }
}
