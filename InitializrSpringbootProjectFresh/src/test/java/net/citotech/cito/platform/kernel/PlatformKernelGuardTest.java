package net.citotech.cito.platform.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.citotech.cito.Common;
import net.citotech.cito.Model.MerchantUser;
import net.citotech.cito.Model.User;
import net.citotech.cito.experience.ExperienceAccessContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class PlatformKernelGuardTest {
    private final ExperienceAccessContext access = mock(ExperienceAccessContext.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final PlatformTenantContextResolver resolver =
            new PlatformTenantContextResolver(access, jdbc);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final PlatformUsageContract usage = mock(PlatformUsageContract.class);
    private final PlatformServiceRuntime runtime =
            new PlatformServiceRuntime(
                    mock(PlatformEntitlementContract.class),
                    usage,
                    mock(PlatformEventContract.class),
                    mock(PlatformAuditContract.class),
                    mock(PlatformOperationalSignalService.class));

    @AfterEach
    void clearCorrelation() {
        MDC.clear();
    }

    @Test
    void merchantIdentityIsReadOnlyAndIgnoresUntrustedApplicationAndTenantHeaders() {
        when(access.require(request, null))
                .thenReturn(
                        new ExperienceAccessContext.Access(
                                "owner@example.invalid", 42L, false, "OWNER"));
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(List.of(9L));
        request.addHeader("X-Cito-Application-ID", "another-tenant-app");
        request.addHeader("X-Merchant-ID", "99");
        request.addHeader("X-Request-ID", "safe-correlation-1");
        PlatformTenantContext context = resolver.require(request, null);
        assertThat(context.merchantId()).isEqualTo(42L);
        assertThat(context.organizationId()).isEqualTo(9L);
        assertThat(context.applicationId()).isEqualTo("CITO_PORTAL");
        assertThat(context.environment()).isEqualTo("SANDBOX");
        assertThat(context.correlationId()).isEqualTo("safe-correlation-1");
        ArgumentCaptor<SqlParameterSource> parameters =
                ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc)
                .queryForList(
                        eq("SELECT id FROM cito_organizations WHERE merchant_id=:merchant_id"),
                        parameters.capture(),
                        eq(Long.class));
        assertThat(parameters.getValue().getValue("merchant_id")).isEqualTo(42L);
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void missingOrganizationRemainsMissingInsteadOfProvisioningOnRead() {
        when(access.require(request, null))
                .thenReturn(
                        new ExperienceAccessContext.Access(
                                "owner@example.invalid", 42L, false, "OWNER"));
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(List.of());
        assertThat(resolver.require(request, null).organizationId()).isNull();
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void unauthenticatedIdentityFailsBeforeDatabaseAccess() {
        when(access.require(request, null))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> resolver.require(request, null))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void adminApiIdentityComesFromVerifiedAccessAndNeedsNoMerchantLookup() {
        when(access.require(request, null))
                .thenReturn(
                        new ExperienceAccessContext.Access(
                                "admin@example.invalid", null, true, "ADMIN_API"));
        request.addHeader("X-Cito-Application-ID", "spoofed");
        request.addHeader("X-CPay-Environment", "production");
        request.addHeader("X-Request-ID", "unsafe\nvalue");
        PlatformTenantContext context = resolver.require(request, null);
        resolver.requireAdmin(context);
        resolver.requireMerchantScope(context, 42L);
        assertThat(context.applicationId()).isEqualTo("CITO_ADMIN_API");
        assertThat(context.environment()).isEqualTo("PRODUCTION");
        assertThat(context.correlationId()).matches("[0-9a-f-]{36}");
        verifyNoInteractions(jdbc);
    }

    @Test
    void scopeNeverTreatsMissingMerchantIdOrAnAdminLabelAloneAsAdministratorAuthority() {
        PlatformTenantContext merchant = merchant("PRODUCTION");
        resolver.requireMerchantScope(merchant, 42L);
        assertThatThrownBy(() -> resolver.requireMerchantScope(merchant, 99L))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> resolver.requireAdmin(merchant))
                .isInstanceOf(ResponseStatusException.class);
        PlatformTenantContext malformed =
                new PlatformTenantContext(
                        null,
                        null,
                        "owner",
                        "MERCHANT_USER",
                        Set.of("OWNER"),
                        "SANDBOX",
                        "CITO_PORTAL",
                        "r");
        assertThatThrownBy(() -> resolver.requireMerchantScope(malformed, 42L))
                .isInstanceOf(ResponseStatusException.class);
        PlatformTenantContext labelled =
                new PlatformTenantContext(
                        null,
                        null,
                        "owner",
                        "ADMIN",
                        Set.of("VIEWER"),
                        "SANDBOX",
                        "CITO_PORTAL",
                        "r");
        assertThatThrownBy(() -> resolver.requireMerchantScope(labelled, 42L))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void sandboxUsageCannotLeakIntoProductionBilling() {
        assertThatThrownBy(
                        () ->
                                runtime.usage(
                                        merchant("SANDBOX"),
                                        "COMMUNICATION_SMS",
                                        "SMS_SEGMENT",
                                        Instant.parse("2026-09-01T10:00:00Z"),
                                        BigDecimal.ONE,
                                        "UGX",
                                        Map.of(),
                                        "message-1",
                                        "comm:SMS:1"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(usage);
    }

    @Test
    void productionUsagePreservesOriginalTimeQuantityReferenceAndIdempotency() {
        Instant occurredAt = Instant.parse("2026-09-01T10:00:00Z");
        runtime.usage(
                merchant("PRODUCTION"),
                "COMMUNICATION_SMS",
                "SMS_SEGMENT",
                occurredAt,
                new BigDecimal("2"),
                "UGX",
                Map.of("provider_code", "TEST"),
                "message-1",
                "comm:SMS:1");
        verify(usage)
                .recordUsage(
                        42L,
                        "COMMUNICATION_SMS",
                        "SMS_SEGMENT",
                        occurredAt,
                        new BigDecimal("2"),
                        "UGX",
                        Map.of("provider_code", "TEST"),
                        "message-1",
                        "comm:SMS:1");
    }

    @Test
    void auditDelegatesToCanonicalMerchantWriterAndNeverCopiesSummaryValues() {
        PlatformAuditGateway gateway = new PlatformAuditGateway(jdbc);
        try (var common = mockStatic(Common.class)) {
            common.when(
                            () ->
                                    Common.recordMerchantAction(
                                            any(MerchantUser.class), anyString(), same(jdbc)))
                    .thenReturn("success");
            gateway.record(
                    merchant("PRODUCTION"),
                    "CHANGED",
                    "TEST",
                    "resource-1",
                    Map.of("credential", "DO-NOT-RECORD-SECRET", "subject", "DO-NOT-RECORD-PII"));
            ArgumentCaptor<MerchantUser> actor = ArgumentCaptor.forClass(MerchantUser.class);
            ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
            common.verify(
                    () ->
                            Common.recordMerchantAction(
                                    actor.capture(), action.capture(), same(jdbc)));
            assertThat(actor.getValue().getMerchant_id()).isEqualTo(42L);
            assertThat(action.getValue())
                    .contains("summaryFields=[credential, subject]")
                    .doesNotContain("DO-NOT-RECORD-SECRET", "DO-NOT-RECORD-PII");
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void unsuccessfulCanonicalAuditFailsClosed() {
        PlatformAuditGateway gateway = new PlatformAuditGateway(jdbc);
        PlatformTenantContext admin =
                new PlatformTenantContext(
                        null,
                        null,
                        "admin",
                        "ADMIN",
                        Set.of("ADMIN"),
                        "PRODUCTION",
                        "CITO_PORTAL",
                        "r");
        try (var common = mockStatic(Common.class)) {
            common.when(() -> Common.recordAction(any(User.class), anyString(), same(jdbc)))
                    .thenReturn("error");
            assertThatThrownBy(() -> gateway.record(admin, "CHANGED", "TEST", "r", Map.of()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    private PlatformTenantContext merchant(String environment) {
        return new PlatformTenantContext(
                9L,
                42L,
                "owner@example.invalid",
                "MERCHANT_USER",
                Set.of("OWNER"),
                environment,
                "CITO_PORTAL",
                "request-1");
    }
}
