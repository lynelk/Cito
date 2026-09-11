package net.citotech.cito.developer.reference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import net.citotech.cito.admin.AdminAuditService;
import net.citotech.cito.billing.pricing.PriceBookAuthoringService;
import net.citotech.cito.billing.pricing.RatedCharge;
import net.citotech.cito.billing.pricing.RatedChargeRepository;
import net.citotech.cito.billing.tenancy.BillingTenantResolver;
import net.citotech.cito.billing.usage.UsageEvent;
import net.citotech.cito.billing.usage.UsageEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

class ApiAccessBillingServiceTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final BillingTenantResolver tenants = mock(BillingTenantResolver.class);
    private final UsageEventRepository usage = mock(UsageEventRepository.class);
    private final RatedChargeRepository charges = mock(RatedChargeRepository.class);
    private final PriceBookAuthoringService prices = mock(PriceBookAuthoringService.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final ApiAccessBillingService service =
            new ApiAccessBillingService(
                    jdbc, tenants, usage, charges, prices, audit, new ObjectMapper());
    private final MockHttpServletRequest request =
            new MockHttpServletRequest("GET", "/api/v2/payments/123");

    @BeforeEach
    void setup() {
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v2/payments/{reference}");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(tenants.resolveTenantId(7)).thenReturn(70L);
        when(jdbc.queryForMap(anyString(), anyMap()))
                .thenReturn(
                        new HashMap<>(
                                Map.of(
                                        "amount",
                                        new BigDecimal("0.0123"),
                                        "currency",
                                        "UGX",
                                        "version_id",
                                        8L)));
    }

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void zeroAndPositiveRatesPreserveFourDecimalsAndExactlyOneChargePerAdmission() {
        service.admitted(7, null, "PRODUCTION");
        service.admitted(7, null, "PRODUCTION");
        ArgumentCaptor<RatedCharge> charge = ArgumentCaptor.forClass(RatedCharge.class);
        verify(charges)
                .insertIfAbsent(
                        eq(70L),
                        eq("API_ACCESS"),
                        anyString(),
                        eq("CUSTOMER_CHARGE"),
                        anyString(),
                        eq(BigDecimal.ONE),
                        charge.capture(),
                        anyString());
        assertThat(charge.getValue().ratedAmount()).isEqualByComparingTo("0.0123");
        assertThat(charge.getValue().priceBookVersionId()).isEqualTo(8L);
        verify(usage, times(1)).insertIfAbsent(any());
    }

    @Test
    void zeroRateIsStillPersistedAsAProductionRatedCharge() {
        when(jdbc.queryForMap(anyString(), anyMap()))
                .thenReturn(
                        new HashMap<>(
                                Map.of(
                                        "amount",
                                        BigDecimal.ZERO,
                                        "currency",
                                        "UGX",
                                        "version_id",
                                        8L)));
        service.admitted(7, null, "PRODUCTION");
        verify(charges)
                .insertIfAbsent(
                        eq(70L),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        argThat(c -> c.ratedAmount().signum() == 0),
                        anyString());
    }

    @Test
    void sandboxKeepsUsageButCannotEnterProductionInvoiceCharges() {
        service.admitted(7, 99L, "SANDBOX");
        ArgumentCaptor<UsageEvent> event = ArgumentCaptor.forClass(UsageEvent.class);
        verify(usage).insertIfAbsent(event.capture());
        assertThat(event.getValue().billingTenantId()).isEqualTo(99L);
        assertThat(event.getValue().dimensions())
                .containsEntry("amount", "0.0000")
                .containsEntry("environment", "SANDBOX");
        verifyNoInteractions(charges);
    }

    @Test
    void aNewHttpRetryCountsAsASeparateAccessAdmission() {
        service.admitted(7, null, "PRODUCTION");
        MockHttpServletRequest retry = new MockHttpServletRequest("GET", "/api/v2/payments/123");
        retry.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v2/payments/{reference}");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(retry));
        service.admitted(7, null, "PRODUCTION");
        ArgumentCaptor<UsageEvent> events = ArgumentCaptor.forClass(UsageEvent.class);
        verify(usage, times(2)).insertIfAbsent(events.capture());
        assertThat(events.getAllValues().get(0).sourceReference())
                .isNotEqualTo(events.getAllValues().get(1).sourceReference());
    }

    @Test
    void failedUsagePersistencePreventsRatedChargeAndAdmissionCompletion() {
        when(usage.insertIfAbsent(any()))
                .thenThrow(new IllegalStateException("storage unavailable"));
        assertThatThrownBy(() -> service.admitted(7, null, "PRODUCTION"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(charges);
    }

    @Test
    void rejectsNegativeOverpreciseAndInvalidCurrencyRatesBeforePublication() {
        for (String value : new String[] {"-1", "0.00001", "10000000000000000"})
            assertThatThrownBy(() -> service.publish("GET", "/x", value, "UGX", 8))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.publish("GET", "/x", "0", "XXXZ", 8))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(prices, audit);
    }

    @Test
    void staleRateVersionCannotOverwriteAnotherAdminsChange() {
        assertThatThrownBy(() -> service.publish("GET", "/x", "0", "UGX", 7))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(prices, audit);
    }

    @Test
    void arbitrarySandboxHeaderDoesNotDiscountProductionOnlyRead() {
        request.addHeader("X-CPay-Environment", "SANDBOX");
        assertThat(service.signedEnvironment(request, "")).isEqualTo("PRODUCTION");
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v2/native/payments/collect");
        assertThat(service.signedEnvironment(request, "{}")).isEqualTo("SANDBOX");
    }

    @Test
    void explicitRefundSandboxDoesNotCreateProductionAccessCharges() {
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v2/refunds/{reference}");
        request.addHeader("X-CPay-Environment", "SANDBOX");
        assertThat(service.signedEnvironment(request, "")).isEqualTo("SANDBOX");
    }
}
