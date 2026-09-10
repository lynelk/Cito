package net.citotech.cito.gateway;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.citotech.cito.*;
import net.citotech.cito.Model.*;
import net.citotech.cito.api.v2.dto.*;
import net.citotech.cito.billing.integration.cpay.PaymentUsageOutboxHook;
import net.citotech.cito.compliance.RiskDecisionService;
import net.citotech.cito.ledger.*;
import net.citotech.cito.merchant.MerchantChannelCryptoService;
import net.citotech.cito.payout.PayoutControlService;
import net.citotech.cito.sandbox.SandboxProductionGuardService;
import net.citotech.cito.sharedprovider.SharedProviderAccessService;
import net.citotech.cito.treasury.ProviderTreasuryService;
import net.citotech.cito.webhook.MerchantWebhookService;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs against the complete migrated disposable MySQL schema. No provider network calls. */
public final class MobileMoneyMysqlScenario {
    public static void run(String url, String user, String password) throws Exception {
        var source = new DriverManagerDataSource(url, user, password);
        var jdbc = new NamedParameterJdbcTemplate(source);
        var tm = new DataSourceTransactionManager(source);
        var transactions = new TransactionTemplate(tm);
        var ledger = new DoubleEntryLedgerService(jdbc);
        var treasury = new ProviderTreasuryService(jdbc);
        var crypto = new MerchantChannelCryptoService("mobile-money-mysql-test-key");
        var usage = mock(PaymentUsageOutboxHook.class);
        var webhooks = new MerchantWebhookService(jdbc, crypto);
        var access = mock(SharedProviderAccessService.class);
        var controls = mock(PayoutControlService.class);
        when(controls.evaluate(any(), any(), anyString()))
                .thenReturn(PayoutControlService.PayoutEvaluation.execute());
        long id = jdbc.queryForObject("SELECT MIN(id) FROM merchants", Map.of(), Long.class);
        Merchant merchant = Common.getMerchantById(Long.toString(id), jdbc);
        // Fixture configurations are synthetic and never reach an operator endpoint.
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
                        call -> {
                            String channel = call.getArgument(1),
                                    environment = call.getArgument(2),
                                    operation = call.getArgument(5);
                            boolean sandbox = "SANDBOX".equals(environment);
                            Map<String, Object> credentials = new HashMap<>();
                            if ("mtn_momo".equals(channel)) {
                                credentials.put(
                                        "baseUrl",
                                        sandbox
                                                ? MtnMomoCredentialSchema.SANDBOX_BASE_URL
                                                : MtnMomoCredentialSchema.PRODUCTION_BASE_URL);
                                credentials.put("baseCurrency", sandbox ? "EUR" : "UGX");
                                credentials.put(
                                        "targetEnvironment", sandbox ? "sandbox" : "mtnuganda");
                                credentials.put("callbackHost", "pay.example.com");
                                credentials.put(
                                        "callbackUrl",
                                        "https://pay.example.com/api/v2/provider-callbacks/mtn");
                                for (String product : List.of("collection", "disbursement"))
                                    for (String field :
                                            List.of("ApiUser", "ApiKey", "SubscriptionKey"))
                                        credentials.put(product + field, "synthetic-test-value");
                            } else {
                                credentials.put(
                                        "baseUrl",
                                        sandbox
                                                ? AirtelOpenApiCredentialSchema.SANDBOX_BASE_URL
                                                : AirtelOpenApiCredentialSchema
                                                        .PRODUCTION_BASE_URL);
                                credentials.put("country", "UG");
                                credentials.put("currency", "UGX");
                                credentials.put("clientId", "synthetic-client");
                                credentials.put("clientSecret", "synthetic-secret");
                            }
                            return new SharedProviderAccessService.CredentialContext(
                                    "PLATFORM_SHARED",
                                    credentials,
                                    null,
                                    "UG",
                                    sandbox && "mtn_momo".equals(channel) ? "EUR" : "UGX",
                                    operation);
                        });
        var gatewayExecution = new GatewayExecutionService();
        var service =
                new MobileMoneyExecutionService(
                        jdbc,
                        tm,
                        access,
                        treasury,
                        new PaymentLedgerSettlementService(ledger, jdbc),
                        mock(RiskDecisionService.class),
                        controls,
                        crypto,
                        new ObjectMapper(),
                        gatewayExecution,
                        usage,
                        webhooks);
        ReflectionTestUtils.setField(
                service, "productionGuard", mock(SandboxProductionGuardService.class));
        ReflectionTestUtils.setField(
                service,
                "routing",
                new IntelligentPaymentRoutingService(
                        mock(PaymentChannelRegistry.class),
                        mock(ChannelCircuitBreaker.class),
                        jdbc));
        var fees = new GatewayChargeDetails();
        fees.setCustomerInboundChargeMethod("flat");
        fees.setCustomerOutboundChargeMethod("flat");
        fees.setCostOfPayInMethod("flat");
        fees.setCostOfPayOutMethod("flat");
        fees.setCustomerInboundCharge(1.25);
        fees.setCustomerOutboundCharge(2.25);
        fees.setCostOfInboundPayment(0.0);
        fees.setCostOfOutboundPayment(0.0);
        var outbound = new AtomicInteger();
        PaymentChannelAdapter mtn = fake("mtn_momo", LegacyGatewayIds.MTN_MOMO, outbound, jdbc);
        PaymentChannelAdapter airtel =
                fake("airtel_open_api", LegacyGatewayIds.AIRTEL_OPEN_API, outbound, jdbc);
        try (var statics = mockStatic(DoPayGateway.class, CALLS_REAL_METHODS)) {
            statics.when(
                            () ->
                                    DoPayGateway.getGatewayChargeDetailsById(
                                            eq(jdbc), anyString(), eq(id)))
                    .thenReturn(fees);
            String collect =
                    service.submit(
                                    request(
                                            merchant,
                                            "mysql-collect",
                                            "100.1250",
                                            "UGX",
                                            "mtn_momo"),
                                    merchant,
                                    "PRODUCTION",
                                    "COLLECT",
                                    mtn)
                            .getTransactionId();
            assertThat(ledger.availableMerchantBalance(id, "UGX")).isEqualByComparingTo("0");
            assertThat(
                            count(
                                    jdbc,
                                    "SELECT COUNT(*) FROM merchant_statement WHERE transactions_log_id=(SELECT id FROM merchant_transactions_log WHERE tx_unique_id='"
                                            + collect
                                            + "')"))
                    .isZero();
            // A failure after ledger and statement writes rolls everything back, including
            // treasury.
            doThrow(new IllegalStateException("synthetic outbox outage"))
                    .when(usage)
                    .recordPaymentSettled(any(), any(), any());
            assertThatThrownBy(
                            () ->
                                    service.apply(
                                            collect, outcome("SUCCESSFUL", "financial-collect")))
                    .hasMessageContaining("synthetic");
            assertThat(service.load(collect).getStatus()).isEqualTo("PENDING");
            assertThat(ledger.availableMerchantBalance(id, "UGX")).isEqualByComparingTo("0");
            assertThat(
                            count(
                                    jdbc,
                                    "SELECT COUNT(*) FROM ledger_transactions WHERE source_reference='"
                                            + collect
                                            + "'"))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM provider_treasury_reservations WHERE merchant_reference='mysql-collect'",
                                    Map.of(),
                                    String.class))
                    .isEqualTo("PENDING");
            doNothing().when(usage).recordPaymentSettled(any(), any(), any());
            try (var workers = Executors.newFixedThreadPool(2)) {
                var first =
                        workers.submit(
                                () ->
                                        service.apply(
                                                collect,
                                                outcome("SUCCESSFUL", "financial-collect")));
                var second =
                        workers.submit(
                                () ->
                                        service.apply(
                                                collect,
                                                outcome("SUCCESSFUL", "financial-collect")));
                first.get(15, TimeUnit.SECONDS);
                second.get(15, TimeUnit.SECONDS);
            }
            assertThat(ledger.availableMerchantBalance(id, "UGX")).isEqualByComparingTo("98.8750");
            assertThat(
                            count(
                                    jdbc,
                                    "SELECT COUNT(*) FROM merchant_statement WHERE transactions_log_id=(SELECT id FROM merchant_transactions_log WHERE tx_unique_id='"
                                            + collect
                                            + "')"))
                    .isEqualTo(2);
            assertThat(
                            count(
                                    jdbc,
                                    "SELECT COUNT(*) FROM ledger_transactions WHERE source_reference='"
                                            + collect
                                            + "'"))
                    .isEqualTo(1);
            assertThat(
                            service.submit(
                                            request(
                                                    merchant,
                                                    "mysql-collect",
                                                    "100.1250",
                                                    "UGX",
                                                    "mtn_momo"),
                                            merchant,
                                            "PRODUCTION",
                                            "COLLECT",
                                            mtn)
                                    .getTransactionId())
                    .isEqualTo(collect);
            assertThat(outbound).hasValue(1);
            // Fund the disposable shared disbursement account through its actual maker/checker
            // workflow.
            long account =
                    jdbc.queryForObject(
                            "SELECT id FROM provider_treasury_accounts WHERE channel_code='mtn_momo' AND environment='PRODUCTION' AND account_role='DISBURSEMENT'",
                            Map.of(),
                            Long.class);
            Map<String, Object> adjustment =
                    transactions.execute(
                            ignored ->
                                    treasury.requestAdjustment(
                                            Map.of(
                                                    "adjustmentType",
                                                    "CREDIT",
                                                    "destinationAccountId",
                                                    account,
                                                    "amount",
                                                    "1000",
                                                    "reason",
                                                    "Disposable CI float",
                                                    "externalReference",
                                                    "mysql-fixture",
                                                    "evidenceReference",
                                                    "fake-provider-only"),
                                            "ci-maker"));
            transactions.executeWithoutResult(
                    ignored ->
                            treasury.approveAdjustment(
                                    ((Number) adjustment.get("id")).longValue(), "ci-checker"));
            String payout =
                    service.submit(
                                    request(merchant, "mysql-payout", "10.0000", "UGX", "mtn_momo"),
                                    merchant,
                                    "PRODUCTION",
                                    "PAYOUT",
                                    mtn)
                            .getTransactionId();
            assertThat(ledger.availableMerchantBalance(id, "UGX")).isEqualByComparingTo("86.6250");
            service.apply(payout, outcome("UNDETERMINED", ""));
            assertThat(ledger.availableMerchantBalance(id, "UGX")).isEqualByComparingTo("86.6250");
            service.apply(payout, outcome("FAILED", ""));
            service.apply(payout, outcome("FAILED", ""));
            assertThat(ledger.availableMerchantBalance(id, "UGX")).isEqualByComparingTo("98.8750");
            String paid =
                    service.submit(
                                    request(
                                            merchant,
                                            "mysql-payout-success",
                                            "10.0000",
                                            "UGX",
                                            "mtn_momo"),
                                    merchant,
                                    "PRODUCTION",
                                    "PAYOUT",
                                    mtn)
                            .getTransactionId();
            service.apply(paid, outcome("SUCCESSFUL", "financial-payout"));
            assertThat(ledger.availableMerchantBalance(id, "UGX")).isEqualByComparingTo("86.6250");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT book_balance FROM provider_treasury_accounts WHERE id=:id",
                                    Map.of("id", account),
                                    BigDecimal.class))
                    .isEqualByComparingTo("990.0000");
            // A managed batch payment adopts its already committed hold without reducing
            // availability twice; stopping the batch cannot free an accepted provider payment.
            jdbc.update(
                    "INSERT INTO merchant_batch_transactions_log(merchant_id,batch_id,status) VALUES (:merchant,'mysql-batch','PROCESSING')",
                    Map.of("merchant", id));
            long batch =
                    jdbc.queryForObject(
                            "SELECT id FROM merchant_batch_transactions_log WHERE batch_id='mysql-batch'",
                            Map.of(),
                            Long.class);
            jdbc.update(
                    "INSERT INTO beneficiaries(batch_id,account,amount,status) VALUES (:batch,'256770000000',10,'UNPAID')",
                    Map.of("batch", batch));
            long beneficiary =
                    jdbc.queryForObject(
                            "SELECT id FROM beneficiaries WHERE batch_id=:batch",
                            Map.of("batch", batch),
                            Long.class);
            String sourceRef = "batch-payout:" + batch + ":" + beneficiary,
                    hold = "batch-payout-reserve:" + batch + ":" + beneficiary;
            transactions.executeWithoutResult(
                    ignored ->
                            ledger.reserve(hold, id, sourceRef, new BigDecimal("12.2500"), "UGX"));
            BigDecimal afterBatchHold = ledger.availableMerchantBalance(id, "UGX");
            String batchPayment =
                    service.submit(
                                    request(merchant, sourceRef, "10", "UGX", "mtn_momo"),
                                    merchant,
                                    "PRODUCTION",
                                    "PAYOUT",
                                    mtn)
                            .getTransactionId();
            assertThat(ledger.availableMerchantBalance(id, "UGX"))
                    .isEqualByComparingTo(afterBatchHold);
            int released =
                    transactions.execute(
                            ignored ->
                                    ledger.releaseReservationsBySourcePrefix(
                                            id, "batch-payout:" + batch + ":"));
            assertThat(released).isZero();
            assertThat(
                            Common.getTxByBatchIdBeneficiaryId(batch, beneficiary, jdbc)
                                    .getTx_unique_id())
                    .isEqualTo(batchPayment);
            service.apply(batchPayment, outcome("FAILED", ""));
            assertThat(ledger.availableMerchantBalance(id, "UGX")).isEqualByComparingTo("86.6250");

            // A refund is a compensating payout. Its refund row must remain PROCESSING until
            // the canonical provider outcome is terminal, and its funds must be reserved once.
            var nativePayments = mock(net.citotech.cito.api.v2.AdapterNativePaymentService.class);
            when(nativePayments.payout(any(), any(), anyString()))
                    .thenAnswer(
                            call ->
                                    service.submit(
                                            call.getArgument(0),
                                            call.getArgument(1),
                                            call.getArgument(2),
                                            "PAYOUT",
                                            mtn));
            new MobileMoneyCompatibilityBridge(nativePayments, "PRODUCTION", jdbc, tm);
            var refundTarget =
                    new net.citotech.cito.refund.RefundService(
                            jdbc,
                            tm,
                            ledger,
                            mock(
                                    net.citotech.cito.merchant.MerchantNotificationPreferenceService
                                            .class),
                            new BigDecimal("500000"));
            ReflectionTestUtils.setField(refundTarget, "paymentWebhooks", webhooks);
            var refundProxy = new org.springframework.aop.framework.ProxyFactory(refundTarget);
            refundProxy.addAdvice(
                    new org.springframework.transaction.interceptor.TransactionInterceptor(
                            tm,
                            new org.springframework.transaction.annotation
                                    .AnnotationTransactionAttributeSource()));
            var refunds = (net.citotech.cito.refund.RefundService) refundProxy.getProxy();
            var refund =
                    refunds.requestRefund(
                            merchant,
                            "mysql-collect",
                            "mysql-refund",
                            new BigDecimal("10"),
                            "Disposable refund");
            assertThat(refund.status()).isEqualTo(net.citotech.cito.refund.RefundStatus.PROCESSING);
            assertThat(ledger.availableMerchantBalance(id, "UGX")).isEqualByComparingTo("74.3750");
            String refundPayment =
                    Common.getMerchantTxByTheirRef("mysql-refund", Long.toString(id), jdbc)
                            .getTx_unique_id();
            service.apply(refundPayment, outcome("SUCCESSFUL", "financial-refund"));
            transactions.executeWithoutResult(ignored -> refunds.synchronizeMobileMoneyRefunds());
            transactions.executeWithoutResult(ignored -> refunds.synchronizeMobileMoneyRefunds());
            assertThat(refunds.findByReference(id, "mysql-refund").orElseThrow().status())
                    .isEqualTo(net.citotech.cito.refund.RefundStatus.COMPLETED);
            assertThat(ledger.availableMerchantBalance(id, "UGX")).isEqualByComparingTo("74.3750");

            // Rejected/cancelled approval has no provider execution and must release its batch
            // hold and conclude a refund, without fabricating a provider failure transaction.
            jdbc.update(
                    "INSERT INTO beneficiaries(batch_id,account,amount,status) VALUES (:batch,'256770000001',10,'INPROGRESS')",
                    Map.of("batch", batch));
            long cancelledBeneficiary =
                    jdbc.queryForObject(
                            "SELECT id FROM beneficiaries WHERE batch_id=:batch AND account='256770000001'",
                            Map.of("batch", batch),
                            Long.class);
            String cancelledBatch = "batch-payout:" + batch + ":" + cancelledBeneficiary;
            transactions.executeWithoutResult(
                    ignored ->
                            ledger.reserve(
                                    "batch-payout-reserve:" + batch + ":" + cancelledBeneficiary,
                                    id,
                                    cancelledBatch,
                                    new BigDecimal("12.2500"),
                                    "UGX"));
            var realControls = new PayoutControlService(jdbc, new ObjectMapper());
            long batchQueue = queue(jdbc, merchant, cancelledBatch);
            transactions.executeWithoutResult(
                    ignored -> assertThat(realControls.cancel(batchQueue, "checker")).isEqualTo(1));
            assertThat(ledger.availableMerchantBalance(id, "UGX")).isEqualByComparingTo("74.3750");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM beneficiaries WHERE id=:id",
                                    Map.of("id", cancelledBeneficiary),
                                    String.class))
                    .isEqualTo("FAILED");
            long refundQueue = queue(jdbc, merchant, "mysql-refund-cancelled");
            jdbc.update(
                    "INSERT INTO refunds(refund_reference,merchant_id,original_transaction_id,original_merchant_ref,requested_amount,refund_status) SELECT 'mysql-refund-cancelled',merchant_id,id,tx_merchant_ref,5,'PROCESSING' FROM merchant_transactions_log WHERE tx_unique_id=:tx",
                    Map.of("tx", collect));
            transactions.executeWithoutResult(
                    ignored ->
                            assertThat(realControls.reject(refundQueue, "checker", "Declined"))
                                    .isEqualTo(1));
            transactions.executeWithoutResult(ignored -> refunds.synchronizeMobileMoneyRefunds());
            assertThat(refunds.findByReference(id, "mysql-refund-cancelled").orElseThrow().status())
                    .isEqualTo(net.citotech.cito.refund.RefundStatus.FAILED);

            long providerOutcomes =
                    count(
                            jdbc,
                            "SELECT COALESCE(SUM(success_count+failure_count),0) FROM provider_health_metrics WHERE channel_code='mtn_momo'");
            assertThat(providerOutcomes).isEqualTo(5);
            long postings = count(jdbc, "SELECT COUNT(*) FROM ledger_transactions");
            String sandbox =
                    service.submit(
                                    request(
                                            merchant,
                                            "mysql-sandbox",
                                            "1000",
                                            "UGX",
                                            "airtel_open_api"),
                                    merchant,
                                    "SANDBOX",
                                    "COLLECT",
                                    airtel)
                            .getTransactionId();
            service.apply(sandbox, outcome("SUCCESSFUL", "fake-sandbox-financial"));
            assertThat(count(jdbc, "SELECT COUNT(*) FROM ledger_transactions")).isEqualTo(postings);
            assertThat(
                            count(
                                    jdbc,
                                    "SELECT COUNT(*) FROM merchant_production_transactions WHERE tx_unique_id='"
                                            + sandbox
                                            + "'"))
                    .isZero();
            assertThat(
                            count(
                                    jdbc,
                                    "SELECT COUNT(*) FROM merchant_sandbox_transactions WHERE tx_unique_id='"
                                            + sandbox
                                            + "'"))
                    .isEqualTo(1);
            assertThat(
                            count(
                                    jdbc,
                                    "SELECT COALESCE(SUM(success_count+failure_count),0) FROM provider_health_metrics WHERE channel_code='mtn_momo'"))
                    .isEqualTo(providerOutcomes);
            assertThat(
                            count(
                                    jdbc,
                                    "SELECT COALESCE(SUM(success_count+failure_count),0) FROM provider_health_metrics WHERE channel_code='airtel_open_api'"))
                    .isZero();
            assertThat(
                            jdbc.queryForList(
                                    "SELECT ledger_account_code FROM provider_treasury_journal WHERE ledger_account_code LIKE 'PROVIDER_FLOAT_PENDING:%' OR ledger_account_code LIKE 'PROVIDER_FLOAT_RESERVED:%' OR ledger_account_code LIKE 'PROVIDER_RECEIVABLE:%' OR ledger_account_code LIKE 'MERCHANT_PENDING_INFLOW:%' GROUP BY ledger_account_code HAVING SUM(CASE WHEN entry_side='DEBIT' THEN amount ELSE -amount END)<>0",
                                    Map.of()))
                    .isEmpty();
            assertThat(ledger.runTrialBalance(java.time.LocalDate.now(), "UGX").isBalanced())
                    .isTrue();
        } finally {
            gatewayExecution.shutdown();
        }
    }

    private static long queue(
            NamedParameterJdbcTemplate jdbc, Merchant merchant, String reference) {
        jdbc.update(
                "INSERT INTO payout_approval_queue(payout_reference,merchant_id,merchant_number,payload_json,amount,currency,trigger_reason,requested_by) VALUES (:reference,:merchant,:number,'{}',10,'UGX','TEST','maker')",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(
                                "reference", reference)
                        .addValue("merchant", merchant.getId())
                        .addValue("number", merchant.getAccount_number()));
        return jdbc.queryForObject(
                "SELECT id FROM payout_approval_queue WHERE merchant_id=:merchant AND payout_reference=:reference",
                Map.of("merchant", merchant.getId(), "reference", reference),
                Long.class);
    }

    private static long count(NamedParameterJdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Map.of(), Long.class);
    }

    private static GateWayResponse outcome(String status, String reference) {
        var result = new GateWayResponse();
        result.setTransactionStatus(status);
        result.setHttpStatus("200");
        result.setNetworkId(reference);
        return result;
    }

    private static PaymentRequest request(
            Merchant merchant, String reference, String amount, String currency, String channel) {
        var request = new PaymentRequest();
        request.setMerchantNumber(merchant.getAccount_number());
        request.setReference(reference);
        request.setAmount(amount);
        request.setCurrency(currency);
        request.setCountry("UG");
        request.setChannel(channel);
        request.setDescription("Disposable database scenario");
        request.setCallbackUrl("");
        var party = new PaymentPartyRequest();
        party.setValue("256770000000");
        request.setPayer(party);
        request.setPayee(party);
        return request;
    }

    private static PaymentChannelAdapter fake(
            String channel,
            String gateway,
            AtomicInteger outbound,
            NamedParameterJdbcTemplate jdbc) {
        return new LegacyGatewayAdapter(channel, channel, "UG", "UGX", gateway) {
            @Override
            public GateWayResponse collect(PaymentGatewayRequest request) {
                outbound.incrementAndGet();
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM mobile_money_executions WHERE transaction_id=:id",
                                        Map.of("id", request.getReference()),
                                        Long.class))
                        .isEqualTo(1);
                return outcome("PENDING", request.getMetadata().get("providerReference"));
            }

            @Override
            public GateWayResponse payout(PaymentGatewayRequest request) {
                return collect(request);
            }
        };
    }
}
