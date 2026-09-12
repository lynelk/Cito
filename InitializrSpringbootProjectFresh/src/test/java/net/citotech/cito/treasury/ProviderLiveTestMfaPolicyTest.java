package net.citotech.cito.treasury;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.citotech.cito.admin.AdminPermissionService;
import net.citotech.cito.api.v2.AdapterNativePaymentService;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.merchant.MerchantChannelCryptoService;
import net.citotech.cito.security.AdminMfaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class ProviderLiveTestMfaPolicyTest {
    private static final String ACTOR = "operator@example.com";
    private NamedParameterJdbcTemplate jdbc;
    private AdapterNativePaymentService payments;
    private AdminMfaService mfa;
    private ProviderLiveTestService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        payments = mock(AdapterNativePaymentService.class);
        mfa = mock(AdminMfaService.class);
        service =
                new ProviderLiveTestService(
                        jdbc,
                        payments,
                        mock(AdminPermissionService.class),
                        mfa,
                        mock(MerchantChannelCryptoService.class));
    }

    private Map<String, Object> collection() {
        Map<String, Object> body = new HashMap<>();
        body.put("operation", "COLLECT");
        body.put("channelCode", "mtn_momo");
        body.put("environment", "PRODUCTION");
        body.put("countryCode", "UG");
        body.put("currencyCode", "UGX");
        body.put("confirmProduction", true);
        return body;
    }

    private void enable() {
        ReflectionTestUtils.setField(service, "collectionMfaSuspendedActor", ACTOR);
        ReflectionTestUtils.setField(
                service, "collectionMfaSuspendedUntil", Instant.now().plusSeconds(600).toString());
    }

    private String rejection(Map<String, Object> body, String actor) {
        return assertThrows(PaymentGatewayException.class, () -> service.request(body, actor))
                .getMessage();
    }

    @Test
    void mfaIsRequiredByDefault() {
        assertEquals("mfaCode is required", rejection(collection(), ACTOR));
        assertEquals(true, service.mfaPolicy(ACTOR).get("mtnCollectionMfaRequired"));
        assertEquals("", service.mfaPolicy(ACTOR).get("suspendedUntil"));
        verifyNoInteractions(jdbc, mfa, payments);
    }

    @Test
    void authorizedCollectionPassesMfaButStillRequiresFinancialRequestFields() {
        enable();
        assertEquals(false, service.mfaPolicy(ACTOR).get("mtnCollectionMfaRequired"));
        assertEquals("idempotencyKey is required", rejection(collection(), ACTOR));
        verifyNoInteractions(jdbc, mfa, payments);
    }

    @Test
    void productionConfirmationIsNeverSuspended() {
        enable();
        Map<String, Object> body = collection();
        body.remove("confirmProduction");
        assertEquals(
                "Production confirmation is required because this test can move real money",
                rejection(body, ACTOR));
        verifyNoInteractions(jdbc, mfa, payments);
    }

    @Test
    void differentActorCannotUseOrImpersonateTheException() {
        enable();
        Map<String, Object> body = collection();
        body.put("actor", ACTOR);
        assertEquals("mfaCode is required", rejection(body, "other@example.com"));
        assertEquals(true, service.mfaPolicy("other@example.com").get("mtnCollectionMfaRequired"));
        assertEquals("", service.mfaPolicy("other@example.com").get("suspendedUntil"));
        verifyNoInteractions(jdbc, mfa, payments);
    }

    @Test
    void missingMalformedAndExpiredConfigurationFailClosed() {
        enable();
        for (String expiry : List.of("", "not-a-date", Instant.now().minusSeconds(1).toString())) {
            ReflectionTestUtils.setField(service, "collectionMfaSuspendedUntil", expiry);
            assertEquals("mfaCode is required", rejection(collection(), ACTOR));
            assertEquals(true, service.mfaPolicy(ACTOR).get("mtnCollectionMfaRequired"));
        }
        enable();
        ReflectionTestUtils.setField(service, "collectionMfaSuspendedActor", "");
        assertEquals("mfaCode is required", rejection(collection(), ACTOR));
        verifyNoInteractions(jdbc, mfa, payments);
    }

    @Test
    void payoutOtherProviderAndOtherCountryOrCurrencyKeepMfa() {
        enable();
        Map<String, String> changes =
                Map.of(
                        "operation", "PAYOUT",
                        "channelCode", "airtel_open_api",
                        "countryCode", "KE",
                        "currencyCode", "USD");
        for (Map.Entry<String, String> change : changes.entrySet()) {
            Map<String, Object> body = collection();
            body.put(change.getKey(), change.getValue());
            assertEquals("mfaCode is required", rejection(body, ACTOR));
        }
        verifyNoInteractions(jdbc, mfa, payments);
    }

    @Test
    void payoutApprovalCannotSpoofCollectionFieldsToSkipMfa() {
        enable();
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "operation", "PAYOUT",
                                        "status", "PENDING_APPROVAL",
                                        "environment", "PRODUCTION",
                                        "requested_by", "maker@example.com")));
        PaymentGatewayException failure =
                assertThrows(
                        PaymentGatewayException.class,
                        () -> service.approve(1L, collection(), ACTOR));
        assertEquals("mfaCode is required", failure.getMessage());
        verifyNoInteractions(mfa, payments);
    }

    @Test
    void sameOperatorStillCannotApproveTheirOwnPayout() {
        enable();
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "operation", "PAYOUT",
                                        "status", "PENDING_APPROVAL",
                                        "environment", "PRODUCTION",
                                        "requested_by", ACTOR)));
        PaymentGatewayException failure =
                assertThrows(
                        PaymentGatewayException.class,
                        () -> service.approve(1L, collection(), ACTOR));
        assertEquals(
                "Maker-checker violation: requester cannot approve the same payout test",
                failure.getMessage());
        verifyNoInteractions(mfa, payments);
    }
}
