package net.citotech.cito.gateway;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.*;
import net.citotech.cito.Model.GateWayResponse;
import net.citotech.cito.Model.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AirtelRecoveryInvariantTest {
    static AirtelRecoveryStore.Entry entry(String source) {
        return new AirtelRecoveryStore.Entry(
                1,
                "00000000-0000-4000-8000-000000000001",
                42,
                "M42",
                "REF42",
                7L,
                "COLLECT",
                "PRODUCTION",
                "UG",
                "UGX",
                source,
                "identity",
                new BigDecimal("100.0000"),
                "hash",
                null,
                0,
                null,
                null);
    }

    static Transaction transaction() {
        Transaction tx = new Transaction();
        tx.setId(7);
        tx.setMerchant_id("42");
        tx.setGateway_id(LegacyGatewayIds.AIRTEL_OPEN_API);
        tx.setTx_merchant_ref("REF42");
        tx.setTx_unique_id(entry("LEGACY_PLATFORM").provider());
        tx.setOriginalAmountDecimal(new BigDecimal("100"));
        tx.setCurrency("UGX");
        tx.setTx_type(Transaction.TX_TYPE_PAYIN);
        return tx;
    }

    @Test
    void originalImmutableTransactionMatches() {
        assertThatCode(
                        () ->
                                AirtelRecoveryService.verifyTransaction(
                                        entry("LEGACY_PLATFORM"), transaction()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsCrossMerchantSettlement() {
        Transaction tx = transaction();
        tx.setMerchant_id("43");
        assertThatThrownBy(
                        () -> AirtelRecoveryService.verifyTransaction(entry("LEGACY_PLATFORM"), tx))
                .hasMessage("AIRTEL_IMMUTABLE_TRANSACTION_MISMATCH");
    }

    @Test
    void rejectsAmountMismatch() {
        Transaction tx = transaction();
        tx.setOriginalAmountDecimal(new BigDecimal("101"));
        assertThatThrownBy(
                        () -> AirtelRecoveryService.verifyTransaction(entry("LEGACY_PLATFORM"), tx))
                .hasMessage("AIRTEL_IMMUTABLE_TRANSACTION_MISMATCH");
    }

    @Test
    void rejectsOperationMismatch() {
        Transaction tx = transaction();
        tx.setTx_type(Transaction.TX_TYPE_PAYOUT);
        assertThatThrownBy(
                        () -> AirtelRecoveryService.verifyTransaction(entry("LEGACY_PLATFORM"), tx))
                .hasMessage("AIRTEL_IMMUTABLE_TRANSACTION_MISMATCH");
    }

    @Test
    void rejectsProviderReferenceMismatch() {
        Transaction tx = transaction();
        tx.setTx_unique_id("different");
        assertThatThrownBy(
                        () -> AirtelRecoveryService.verifyTransaction(entry("LEGACY_PLATFORM"), tx))
                .hasMessage("AIRTEL_IMMUTABLE_TRANSACTION_MISMATCH");
    }

    @Test
    void secretRotationKeepsAccountIdentityButClientChangeDoesNot() {
        Map<String, String> a = AirtelStatusClientTest.credentials();
        String first = AirtelRecoveryCredentials.identity(a, "SANDBOX", "UG", "UGX");
        a.put("clientSecret", "rotated");
        assertThat(AirtelRecoveryCredentials.identity(a, "SANDBOX", "UG", "UGX")).isEqualTo(first);
        a.put("clientId", "another");
        assertThat(AirtelRecoveryCredentials.identity(a, "SANDBOX", "UG", "UGX"))
                .isNotEqualTo(first);
    }

    @ParameterizedTest
    @ValueSource(strings = {"400", "401", "403", "422"})
    void explicitDeclinesCanBePersisted(String code) {
        GateWayResponse r = new GateWayResponse();
        r.setHttpStatus(code);
        r.setTransactionStatus("FAILED");
        assertThat(AirtelRecoveryService.rejectionProof(r))
                .isEqualTo("SUBMISSION_REJECTED_" + code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "202", "408", "409", "429", "500", "invalid"})
    void ambiguousResponsesAreNotFailureEvidence(String code) {
        GateWayResponse r = new GateWayResponse();
        r.setHttpStatus(code);
        r.setTransactionStatus("FAILED");
        assertThat(AirtelRecoveryService.rejectionProof(r)).isNull();
    }

    @Test
    void callbackPayloadCannotSettleOrInventReferences() {
        AirtelRecoveryStore store = mock(AirtelRecoveryStore.class);
        AirtelRecoveryCallbackController c = new AirtelRecoveryCallbackController(store);
        c.receiveBody(
                Map.of(
                        "transaction",
                        Map.of(
                                "id",
                                entry("MERCHANT").provider(),
                                "status",
                                "SUCCESSFUL",
                                "amount",
                                "10000000")));
        verify(store).signal(entry("MERCHANT").provider());
        verifyNoMoreInteractions(store);
        c.receive("not-a-valid-reference");
        verifyNoMoreInteractions(store);
    }

    @Test
    void productionAdapterCannotBypassCanonicalOrchestration() {
        AirtelRecoveryService s =
                new AirtelRecoveryService(
                        null, null, null, null, null, null, null, null, "PRODUCTION");
        PaymentGatewayRequest r =
                new PaymentGatewayRequest(
                        "M42",
                        "256700000000",
                        100.0,
                        "REF42",
                        "test",
                        null,
                        Map.of("gatewayState", "PRODUCTION"));
        assertThatThrownBy(() -> s.submit(r, "COLLECT"))
                .hasMessage("AIRTEL_PRODUCTION_REQUIRES_CANONICAL_ORCHESTRATION");
    }
}
