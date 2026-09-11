package net.citotech.cito.gateway;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.citotech.cito.*;
import net.citotech.cito.Model.*;
import net.citotech.cito.api.v2.dto.*;
import net.citotech.cito.billing.integration.cpay.PaymentUsageOutboxHook;
import net.citotech.cito.compliance.RiskDecisionService;
import net.citotech.cito.ledger.PaymentLedgerSettlementService;
import net.citotech.cito.merchant.MerchantChannelCryptoService;
import net.citotech.cito.payout.PayoutControlService;
import net.citotech.cito.sandbox.SandboxProductionGuardService;
import net.citotech.cito.sharedprovider.SharedProviderAccessService;
import net.citotech.cito.treasury.ProviderTreasuryService;
import net.citotech.cito.webhook.MerchantWebhookService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

class MobileMoneyExecutionServiceTest {
    NamedParameterJdbcTemplate jdbc;
    MobileMoneyExecutionService service;
    SharedProviderAccessService access = mock(SharedProviderAccessService.class);
    PaymentLedgerSettlementService ledger = mock(PaymentLedgerSettlementService.class);
    RiskDecisionService risk = mock(RiskDecisionService.class);
    PayoutControlService controls = mock(PayoutControlService.class);
    GatewayExecutionService execution = new GatewayExecutionService();
    AtomicInteger outbound = new AtomicInteger();
    Merchant merchant = new Merchant();
    GatewayChargeDetails fees = new GatewayChargeDetails();
    PaymentChannelAdapter adapter =
            new LegacyGatewayAdapter("mtn_momo", "MTN", "UG", "UGX", LegacyGatewayIds.MTN_MOMO) {
                @Override
                public GateWayResponse collect(PaymentGatewayRequest request) {
                    outbound.incrementAndGet();
                    // Read using a different connection while the provider is executing: evidence
                    // must already be committed.
                    assertThat(
                                    jdbc.getJdbcTemplate()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM mobile_money_executions WHERE provider_reference=?",
                                                    Integer.class,
                                                    request.getMetadata().get("providerReference")))
                            .isEqualTo(1);
                    GateWayResponse pending = new GateWayResponse();
                    pending.setTransactionStatus("PENDING");
                    pending.setHttpStatus("202");
                    return pending;
                }

                @Override
                public GateWayResponse payout(PaymentGatewayRequest request) {
                    return collect(request);
                }
            };

    @BeforeEach
    void setup() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL(
                "jdbc:h2:mem:"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new NamedParameterJdbcTemplate(source);
        jdbc.getJdbcTemplate()
                .execute(
                        "CREATE TABLE merchants(id BIGINT PRIMARY KEY,account_number VARCHAR(100))");
        jdbc.getJdbcTemplate()
                .execute(
                        "CREATE TABLE provider_endpoint_runs(merchant_number VARCHAR(100),reference_value VARCHAR(255),environment VARCHAR(16),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.getJdbcTemplate().execute("INSERT INTO merchants VALUES(10,'M10')");
        jdbc.getJdbcTemplate()
                .execute("CREATE TABLE settings(name VARCHAR(100),setting_value VARCHAR(255))");
        jdbc.getJdbcTemplate()
                .execute(
                        "CREATE TABLE merchant_transactions_log(id BIGINT AUTO_INCREMENT PRIMARY KEY,merchant_id BIGINT,merchant_batch_transactions_log_id BIGINT,beneficiary_id BIGINT,gateway_id VARCHAR(80),original_amount DECIMAL(19,4),currency VARCHAR(8),execution_environment VARCHAR(16),tx_type VARCHAR(40),charges DECIMAL(19,4),tx_cost DECIMAL(19,4),charging_method VARCHAR(40),tx_unique_id VARCHAR(64),tx_gateway_ref VARCHAR(100),tx_merchant_ref VARCHAR(255),payer_number VARCHAR(30),tx_description VARCHAR(255),tx_merchant_description VARCHAR(255),callback_url VARCHAR(255),status VARCHAR(40),callback_status VARCHAR(40),tx_request_trace TEXT,tx_update_trace TEXT,originate_ip VARCHAR(100),safaricom_request_reference VARCHAR(100),created_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP,callback_trace TEXT, UNIQUE(merchant_id,tx_merchant_ref))");
        String migration =
                Files.readString(
                        Path.of(
                                "src/main/resources/db/migration/V126__mobile_money_execution_integrity.sql"));
        jdbc.getJdbcTemplate().execute(migration.substring(0, migration.indexOf("ALTER TABLE")));
        for (String column :
                List.of(
                        "recovery_claim_token VARCHAR(36)",
                        "recovery_claim_until TIMESTAMP(6)",
                        "recovery_attempt_count INT NOT NULL DEFAULT 0",
                        "recovery_last_code VARCHAR(64)",
                        "recovery_last_signal_at TIMESTAMP(6)")) {
            jdbc.getJdbcTemplate()
                    .execute("ALTER TABLE mobile_money_executions ADD COLUMN " + column);
        }
        merchant.setId(10L);
        merchant.setAccount_number("M10");
        merchant.setStatus("ACTIVE");
        fees.setCustomerInboundChargeMethod("flat");
        fees.setCustomerOutboundChargeMethod("flat");
        fees.setCostOfPayInMethod("flat");
        fees.setCostOfPayOutMethod("flat");
        fees.setCustomerInboundCharge(1.25);
        fees.setCustomerOutboundCharge(2.25);
        fees.setCostOfInboundPayment(0.5);
        fees.setCostOfOutboundPayment(0.5);
        Map<String, Object> credentials = new HashMap<>();
        credentials.put("baseUrl", MtnMomoCredentialSchema.PRODUCTION_BASE_URL);
        credentials.put("baseCurrency", "UGX");
        credentials.put("targetEnvironment", "mtnuganda");
        credentials.put("callbackHost", "pay.example.com");
        credentials.put("callbackUrl", "https://pay.example.com/api/v2/provider-callbacks/mtn");
        for (String product : List.of("collection", "disbursement"))
            for (String field : List.of("ApiUser", "ApiKey", "SubscriptionKey"))
                credentials.put(product + field, "synthetic-test-value");
        when(access.resolve(
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        nullable(String.class)))
                .thenAnswer(
                        call ->
                                new SharedProviderAccessService.CredentialContext(
                                        "MERCHANT",
                                        credentials,
                                        null,
                                        "UG",
                                        "UGX",
                                        call.getArgument(5)));
        when(controls.evaluate(any(), any(), anyString()))
                .thenReturn(PayoutControlService.PayoutEvaluation.execute());
        service =
                new MobileMoneyExecutionService(
                        jdbc,
                        new DataSourceTransactionManager(source),
                        access,
                        mock(ProviderTreasuryService.class),
                        ledger,
                        risk,
                        controls,
                        new MerchantChannelCryptoService("synthetic-local-key"),
                        new ObjectMapper(),
                        execution,
                        mock(PaymentUsageOutboxHook.class),
                        mock(MerchantWebhookService.class));
        ReflectionTestUtils.setField(
                service, "productionGuard", mock(SandboxProductionGuardService.class));
    }

    @AfterEach
    void close() {
        execution.shutdown();
    }

    PaymentRequest request() {
        PaymentRequest request = new PaymentRequest();
        request.setMerchantNumber("M10");
        request.setReference("order-1");
        request.setAmount("100.1250");
        request.setCountry("UG");
        request.setCurrency("UGX");
        request.setChannel("mtn_momo");
        request.setCallbackUrl("https://merchant.example/callback");
        request.setDescription("order");
        PaymentPartyRequest party = new PaymentPartyRequest();
        party.setValue("256770000000");
        request.setPayer(party);
        request.setPayee(party);
        return request;
    }

    PaymentResult submit(PaymentRequest request, String operation) {
        try (var gateway = mockStatic(DoPayGateway.class, CALLS_REAL_METHODS)) {
            gateway.when(
                            () ->
                                    DoPayGateway.getGatewayChargeDetailsById(
                                            jdbc, LegacyGatewayIds.MTN_MOMO, 10L))
                    .thenReturn(fees);
            return service.submit(request, merchant, "PRODUCTION", operation, adapter);
        }
    }

    @Test
    void concurrentReferenceReplaysSubmitOnceAndShareCanonicalId() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Callable<PaymentResult> call =
                    () -> {
                        start.await();
                        return submit(request(), "COLLECT");
                    };
            Future<PaymentResult> first = threads.submit(call), second = threads.submit(call);
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).getTransactionId())
                    .isEqualTo(second.get(10, TimeUnit.SECONDS).getTransactionId());
        }
        assertThat(outbound).hasValue(1);
        verify(risk, times(1)).authorizePayment(any(), any(), eq("COLLECT"));
        assertThat(
                        jdbc.getJdbcTemplate()
                                .queryForObject(
                                        "SELECT credential_snapshot FROM mobile_money_executions",
                                        String.class))
                .doesNotContain("synthetic-test-value");
    }

    @Test
    void conflictingAmountCannotReuseTheProviderSubmission() {
        submit(request(), "COLLECT");
        PaymentRequest changed = request();
        changed.setAmount("100.1251");
        assertThatThrownBy(() -> submit(changed, "COLLECT"))
                .isInstanceOf(PaymentGatewayException.class)
                .hasMessageContaining("conflicts");
        assertThat(outbound).hasValue(1);
    }

    @Test
    void productionCapIsSerializedAndExistingReferencesRemainReplayable() throws Exception {
        for (int i = 0; i < 9; i++) {
            PaymentRequest next = request();
            next.setReference("cap-" + i);
            submit(next, "COLLECT");
        }
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 9; i < 11; i++) {
                final String reference = "cap-" + i;
                futures.add(
                        workers.submit(
                                () -> {
                                    start.await();
                                    PaymentRequest next = request();
                                    next.setReference(reference);
                                    try {
                                        submit(next, "COLLECT");
                                        accepted.incrementAndGet();
                                    } catch (PaymentGatewayException capped) {
                                        assertThat(capped.getMessage())
                                                .containsIgnoringCase("limit");
                                        rejected.incrementAndGet();
                                    }
                                    return null;
                                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertThat(accepted).hasValue(1);
        assertThat(rejected).hasValue(1);
        PaymentRequest replay = request();
        replay.setReference("cap-0");
        assertThat(submit(replay, "COLLECT").getStatus()).isEqualTo("PENDING");
        assertThat(outbound).hasValue(10);
    }

    @Test
    void payoutReservesMerchantFundsBeforeNetworkAndPendingDoesNotReleaseThem() {
        submit(request(), "PAYOUT");
        verify(ledger).reservePayout(any(), eq(merchant));
        verify(ledger, never()).releaseUnsubmittedPayout(any(), any());
        verify(ledger, never()).applyTerminalProviderOutcome(any(), anyString(), any());
        assertThat(outbound).hasValue(1);
    }

    @Test
    void insufficientMerchantFundsRollBackTheClaimAndNeverCallProvider() {
        doThrow(new PaymentGatewayException("Insufficient funds"))
                .when(ledger)
                .reservePayout(any(), any());
        assertThatThrownBy(() -> submit(request(), "PAYOUT")).hasMessageContaining("Insufficient");
        assertThat(outbound).hasValue(0);
        assertThat(
                        jdbc.getJdbcTemplate()
                                .queryForObject(
                                        "SELECT COUNT(*) FROM merchant_transactions_log",
                                        Integer.class))
                .isZero();
    }

    @Test
    void approvalRequiredDoesNotReserveOrSubmitMoney() {
        when(controls.evaluate(any(), any(), anyString()))
                .thenReturn(
                        PayoutControlService.PayoutEvaluation.approvalRequired(
                                "LIMIT", "approval", 9));
        assertThat(submit(request(), "PAYOUT").getStatus()).isEqualTo("APPROVAL_PENDING");
        assertThat(outbound).hasValue(0);
        verifyNoInteractions(ledger);
    }
}
