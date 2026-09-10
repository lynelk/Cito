package net.citotech.cito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import net.citotech.cito.Model.Balance;
import net.citotech.cito.Model.GatewayChargeDetails;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.Model.Setting;
import net.citotech.cito.api.v2.IdempotencyService;
import net.citotech.cito.ledger.DoubleEntryLedgerService;
import net.citotech.cito.ledger.LegacyLedgerPostingService;
import net.citotech.cito.payout.PayoutControlService;
import net.citotech.cito.security.CallbackUrlValidator;
import net.citotech.cito.security.SignatureVerificationService;
import net.citotech.cito.service.RateLimiterService;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * S2: controller-level coverage of the v1 payout endpoint ({@code Api.doMobileMoneyPayOut}). The
 * endpoint's wiring is exercised directly - full validation run, then the payout-control gate
 * decides before any money movement. A breached control must park the payout (000 envelope, no
 * ledger reservation, no provider call); a clean request must reserve the exact BigDecimal and
 * proceed to execution.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class ApiV1PayoutEndpointTest {

    private static final String GATEWAY_ID = "MTNMoMoPaymentGateway";

    @Test
    void parkedPayoutReturnsParkedEnvelopeWithoutReservingOrCallingProvider() throws Exception {
        NamedParameterJdbcTemplate controlJdbc = mock(NamedParameterJdbcTemplate.class);
        when(controlJdbc.queryForObject(
                        anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(0);
        stubControl(controlJdbc, new BigDecimal("1000"), null, new BigDecimal("500"), null, "NO");
        when(controlJdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        when(controlJdbc.queryForObject(
                        anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(42L);
        PayoutControlService controlService =
                new PayoutControlService(
                        controlJdbc, new com.fasterxml.jackson.databind.ObjectMapper());

        Api api = apiWithDefaults(controlService);
        DoubleEntryLedgerService ledger = ledger(api);

        String response = null;
        try (MockedStatic<Common> common = mockStatic(Common.class);
                MockedStatic<DoPayGateway> doPay = mockStatic(DoPayGateway.class);
                MockedStatic<SignatureVerificationService> signature =
                        mockStatic(SignatureVerificationService.class);
                MockedStatic<CallbackUrlValidator> urlValidator =
                        mockStatic(CallbackUrlValidator.class)) {
            stubStatics(common, doPay, signature, urlValidator);
            response = execute(api, payoutBody("900.00", "REF-PARK-0", "256700000001"), null);
        }

        JSONObject json = new JSONObject(response);
        assertThat(json.getString("code")).isEqualTo("000");
        assertThat(json.getString("message")).contains("Payout requires maker-checker approval");
        assertThat(json.getString("message")).contains("PER_TRANSACTION_LIMIT");
        verify(ledger, never())
                .reserve(anyString(), anyLong(), anyString(), any(BigDecimal.class), anyString());
        verify(ledger, never()).captureReservation(anyString());
    }

    @Test
    void cleanPayoutReservesExactBigDecimalThenExecutes() throws Exception {
        NamedParameterJdbcTemplate controlJdbc = mock(NamedParameterJdbcTemplate.class);
        when(controlJdbc.queryForObject(
                        anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(0);
        when(controlJdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        Api api =
                apiWithDefaults(
                        new PayoutControlService(
                                controlJdbc, new com.fasterxml.jackson.databind.ObjectMapper()));
        DoubleEntryLedgerService ledger = ledger(api);

        String response = null;
        try (MockedStatic<Common> common = mockStatic(Common.class);
                MockedStatic<DoPayGateway> doPay = mockStatic(DoPayGateway.class);
                MockedStatic<SignatureVerificationService> signature =
                        mockStatic(SignatureVerificationService.class);
                MockedStatic<CallbackUrlValidator> urlValidator =
                        mockStatic(CallbackUrlValidator.class)) {
            stubStatics(common, doPay, signature, urlValidator);
            response = execute(api, payoutBody("900.00", "REF-GO-0", "256700000001"), null);
        }

        JSONObject json = new JSONObject(response);
        assertThat(json.getString("code")).isEqualTo("000");
        verify(ledger)
                .reserve(
                        eq("v1-payout-reserve:M100:REF-GO-0:tx-uuid-1"),
                        eq(7L),
                        eq("REF-GO-0"),
                        argThat(
                                (BigDecimal value) ->
                                        value.compareTo(new BigDecimal("900.00")) == 0),
                        eq("UGX"));
        verify(ledger).captureReservation("v1-payout-reserve:M100:REF-GO-0:tx-uuid-1");
    }

    @Test
    void parkedPayoutRecordsIdempotencyBodyWhenKeyHeaderIsSent() throws Exception {
        NamedParameterJdbcTemplate controlJdbc = mock(NamedParameterJdbcTemplate.class);
        when(controlJdbc.queryForObject(
                        anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(0);
        stubControl(controlJdbc, new BigDecimal("1000"), null, new BigDecimal("500"), null, "NO");
        when(controlJdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        when(controlJdbc.queryForObject(
                        anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(42L);
        Api api =
                apiWithDefaults(
                        new PayoutControlService(
                                controlJdbc, new com.fasterxml.jackson.databind.ObjectMapper()));
        IdempotencyService idempotency = idempotency(api);

        try (MockedStatic<Common> common = mockStatic(Common.class);
                MockedStatic<DoPayGateway> doPay = mockStatic(DoPayGateway.class);
                MockedStatic<SignatureVerificationService> signature =
                        mockStatic(SignatureVerificationService.class);
                MockedStatic<CallbackUrlValidator> urlValidator =
                        mockStatic(CallbackUrlValidator.class)) {
            stubStatics(common, doPay, signature, urlValidator);
            execute(api, payoutBody("900.00", "REF-PARK-1", "256700000001"), "idem-key-1");
        }

        verify(idempotency)
                .recordBody(
                        eq("M100"),
                        eq("idem-key-1"),
                        anyString(),
                        argThat(
                                (String message) ->
                                        message.contains(
                                                "Payout requires maker-checker approval")));
    }

    private void stubStatics(
            MockedStatic<Common> common,
            MockedStatic<DoPayGateway> doPay,
            MockedStatic<SignatureVerificationService> signature,
            MockedStatic<CallbackUrlValidator> urlValidator) {
        Merchant merchant = merchant();
        common.when(() -> Common.getIpAddress(any())).thenReturn("127.0.0.1");
        common.when(() -> Common.getMerchantByAccountNumber(anyString(), any()))
                .thenReturn(merchant);
        common.when(() -> Common.getMerchantSettings(anyString(), any(), any())).thenReturn(null);
        common.when(() -> Common.getSettings(anyString(), any())).thenAnswer(call -> setting());
        common.when(() -> Common.getMerchantBalances(anyString(), any()))
                .thenReturn(new ArrayList<>(List.of(balance())));
        common.when(Common::generateUuid).thenReturn("tx-uuid-1");
        common.when(() -> Common.doPayOut(any(), any(), any(), any()))
                .thenReturn(GeneralSuccessResponse.getMessage("000", "executed"));

        GatewayChargeDetails charges = new GatewayChargeDetails();
        charges.setCustomerOutboundChargeMethod("flat");
        charges.setCustomerOutboundCharge(0.0);
        charges.setCostOfPayOutMethod("flat");
        charges.setCostOfOutboundPayment(0.0);
        doPay.when(() -> DoPayGateway.getGatewayIdByMsisdn(anyString(), any()))
                .thenReturn(GATEWAY_ID);
        doPay.when(() -> DoPayGateway.getGatewayChargeDetailsById(any(), anyString(), any()))
                .thenReturn(charges);
        doPay.when(() -> DoPayGateway.getCustomerOutboundCharges(any(), any())).thenReturn(0.0);
        doPay.when(() -> DoPayGateway.getCostOfOutboundCharges(any(), any())).thenReturn(0.0);

        signature
                .when(() -> SignatureVerificationService.verify(any(), anyString(), anyString()))
                .thenReturn(null);
        urlValidator.when(() -> CallbackUrlValidator.validate(anyString())).thenReturn(null);
    }

    private String execute(Api api, String body, String idempotencyKey) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Idempotency-Key")).thenReturn(idempotencyKey);
        when(request.getHeader("X-Idempotency-Key")).thenReturn(null);
        HttpServletResponse response = mock(HttpServletResponse.class);
        return api.doMobileMoneyPayOut(body, request, response);
    }

    private String payoutBody(String amount, String reference, String payeeNumber) {
        return "{\"amount\":\""
                + amount
                + "\",\"description\":\"payout\",\"reference\":\""
                + reference
                + "\",\"merchant_number\":\"M100\",\"payee_number\":\""
                + payeeNumber
                + "\",\"callback_url\":\"https://merchant.example/cb\","
                + "\"signature\":\"sig-b64\"}";
    }

    private Api apiWithDefaults(PayoutControlService controlService) throws Exception {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        Api api = new Api();
        setField(
                api,
                "apiBilling",
                mock(net.citotech.cito.developer.reference.ApiAccessBillingService.class));
        setField(api, "jdbcTemplate", jdbcTemplate);
        setField(api, "transactionManager", mock(PlatformTransactionManager.class));
        RateLimiterService rateLimiter = mock(RateLimiterService.class);
        when(rateLimiter.tryConsume(anyString())).thenReturn(true);
        setField(api, "rateLimiterService", rateLimiter);
        setField(api, "legacyLedgerPostingService", mock(LegacyLedgerPostingService.class));
        setField(api, "ledgerService", mock(DoubleEntryLedgerService.class));
        setField(api, "idempotencyService", mock(IdempotencyService.class));
        setField(api, "payoutControlService", controlService);
        return api;
    }

    private void setField(Api api, String name, Object value) throws Exception {
        Field field = Api.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(api, value);
    }

    private Merchant merchant() {
        Merchant merchant = new Merchant();
        merchant.setId(7L);
        merchant.setAccount_number("M100");
        merchant.setName("Test Merchant");
        merchant.setShort_name("Test");
        merchant.setStatus("ACTIVE");
        merchant.setAllowed_apis(new String[] {Common.API_MOBILE_MONEY_PAYOUT});
        return merchant;
    }

    private Setting setting() {
        Setting setting = new Setting();
        setting.setSetting_value("100");
        return setting;
    }

    private Balance balance() {
        return new Balance("UGX", 100000.0, GATEWAY_ID);
    }

    private DoubleEntryLedgerService ledger(Api api) throws Exception {
        Field field = Api.class.getDeclaredField("ledgerService");
        field.setAccessible(true);
        return (DoubleEntryLedgerService) field.get(api);
    }

    private IdempotencyService idempotency(Api api) throws Exception {
        Field field = Api.class.getDeclaredField("idempotencyService");
        field.setAccessible(true);
        return (IdempotencyService) field.get(api);
    }

    /** Stubs the private Control RowMapper the same way PayoutControlServiceTest does. */
    private void stubControl(
            NamedParameterJdbcTemplate jdbcTemplate,
            BigDecimal daily,
            BigDecimal monthly,
            BigDecimal perTx,
            Integer velocity,
            String approvalRequired) {
        ResultSet row = mock(ResultSet.class);
        try {
            when(row.getBigDecimal("daily_amount_limit")).thenReturn(daily);
            when(row.getBigDecimal("monthly_amount_limit")).thenReturn(monthly);
            when(row.getBigDecimal("per_transaction_limit")).thenReturn(perTx);
            when(row.getObject("beneficiary_velocity_limit")).thenReturn(velocity);
            when(row.getString("approval_required_flag")).thenReturn(approvalRequired);
            when(row.getString("enabled_flag")).thenReturn("YES");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(jdbcTemplate.query(
                        anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(
                        invocation -> {
                            RowMapper mapper = invocation.getArgument(2);
                            return List.of(mapper.mapRow(row, 1));
                        });
    }
}
