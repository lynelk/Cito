package net.citotech.cito.communication.outbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.citotech.cito.communication.MerchantCommunicationService;
import net.citotech.cito.communication.delivery.CommunicationDeliveryDispatcher;
import net.citotech.cito.communication.delivery.DeliveryLogRepository;
import net.citotech.cito.communication.domain.CommunicationChannel;
import net.citotech.cito.communication.email.EmailDeliveryService;
import net.citotech.cito.communication.notification.NotificationOrchestrator;
import net.citotech.cito.communication.preference.PreferenceService;
import net.citotech.cito.communication.provider.*;
import net.citotech.cito.communication.routing.SmartSmsRoutingService;
import net.citotech.cito.communication.sms.SmsConversationService;
import net.citotech.cito.communication.sms.SmsEncodingService;
import net.citotech.cito.communication.template.TemplateService;
import net.citotech.cito.merchant.MerchantNotificationPreferenceService;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executed only by the clean disposable MySQL migration gate, with two in-memory fake providers.
 */
public final class NotificationMysqlScenario {
    public static void run(String url, String user, String password) {
        var dataSource = new DriverManagerDataSource(url, user, password);
        var jdbc = new NamedParameterJdbcTemplate(dataSource);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var mapper = new ObjectMapper();
        var encoding = new SmsEncodingService();
        var logs = new DeliveryLogRepository(jdbc);
        var first = new FakeProvider("FAKE_FIRST", true);
        var second = new FakeProvider("FAKE_SECOND", false);
        var registry = new ProviderRegistry(List.of(first, second));
        var health = new CommunicationProviderHealthService(jdbc, 99, 60);
        var router = new SmartSmsRoutingService(jdbc, registry, health, encoding, mapper);
        var dispatcher =
                new CommunicationDeliveryDispatcher(
                        logs, registry, mock(EmailDeliveryService.class), router);
        var worker = new CommunicationOutboxWorker(jdbc, dispatcher, health, 100, 5);
        var preferences = new MerchantNotificationPreferenceService(jdbc);
        var orchestrator =
                new NotificationOrchestrator(
                        jdbc,
                        preferences,
                        new PreferenceService(jdbc),
                        new TemplateService(jdbc),
                        encoding,
                        mapper);
        var merchantService = new MerchantCommunicationService(jdbc, mapper, encoding, router);
        long merchant = jdbc.queryForObject("SELECT MIN(id) FROM merchants", Map.of(), Long.class);
        jdbc.update(
                "INSERT INTO merchant_admins(merchant_id,name,email,phone,status) VALUES (:merchant,'Notification CI','notification-ci@example.invalid','+256700000001','ACTIVE')",
                Map.of("merchant", merchant));
        jdbc.update("UPDATE communication_providers SET enabled_flag='NO'", Map.of());
        for (String code : List.of(first.providerCode(), second.providerCode())) {
            jdbc.update(
                    "INSERT INTO communication_providers(provider_code,provider_name,channel,adapter_class,enabled_flag) VALUES (:code,:code,'SMS','FAKE','YES')",
                    Map.of("code", code));
            jdbc.update(
                    "INSERT INTO communication_routing_rules(channel,merchant_id,priority,provider_code,enabled_flag) VALUES ('SMS',NULL,:priority,:code,'YES')",
                    Map.of("code", code, "priority", code.equals(first.providerCode()) ? 1 : 2));
        }
        preferences.save(merchant, "payment.completed", "NONE", null);
        tx.executeWithoutResult(
                status ->
                        orchestrator.record(
                                merchant, "ci-payment", "payment.completed", "ci-payment"));
        long event =
                jdbc.queryForObject(
                        "SELECT id FROM notification_events WHERE event_id='ci-payment' AND merchant_id=:merchant",
                        Map.of("merchant", merchant),
                        Long.class);
        tx.executeWithoutResult(status -> orchestrator.process(event));
        worker.processDue(100); // Fake first provider fails definitively.
        jdbc.update(
                "UPDATE communication_outbox SET next_attempt_at=NOW() WHERE status='PENDING'",
                Map.of());
        worker.processDue(100); // Router must exclude the failed provider for this message.
        assertEquals(1, first.calls.get());
        assertEquals(1, second.calls.get());
        assertEquals(
                "SENT",
                jdbc.queryForObject(
                        "SELECT m.status FROM communication_messages m JOIN notification_evidence e ON e.communication_id=m.id WHERE e.event_row_id=:event",
                        Map.of("event", event),
                        String.class));
        tx.executeWithoutResult(
                status -> {
                    orchestrator.record(merchant, "ci-payment", "payment.completed", "ci-payment");
                    orchestrator.process(event);
                });
        worker.processDue(100);
        assertEquals(1, second.calls.get(), "Duplicate event must not send again");
        jdbc.update(
                "INSERT INTO communication_sender_identities(merchant_id,sender_id,provider_code,approval_status,inbound_token) VALUES (:merchant,'CitoCI','FAKE_SECOND','APPROVED','ci-only-callback-token-for-fake-provider')",
                Map.of("merchant", merchant));
        var conversations = new SmsConversationService(jdbc, logs, mapper);
        tx.executeWithoutResult(
                status ->
                        conversations.receiveDeliveryReceipt(
                                "ci-only-callback-token-for-fake-provider",
                                "FAKE_SECOND",
                                "fake-1",
                                "delivered",
                                Map.of()));
        assertEquals(
                "DELIVERED",
                jdbc.queryForObject(
                        "SELECT m.status FROM communication_messages m JOIN notification_evidence e ON e.communication_id=m.id WHERE e.event_row_id=:event",
                        Map.of("event", event),
                        String.class));
        tx.executeWithoutResult(
                status ->
                        conversations.receiveDeliveryReceipt(
                                "ci-only-callback-token-for-fake-provider",
                                "FAKE_SECOND",
                                "fake-1",
                                "failed",
                                Map.of()));
        assertEquals(
                "DELIVERED",
                jdbc.queryForObject(
                        "SELECT m.status FROM communication_messages m JOIN notification_evidence e ON e.communication_id=m.id WHERE e.event_row_id=:event",
                        Map.of("event", event),
                        String.class),
                "Late failure must not regress delivery");
        jdbc.update(
                "INSERT INTO admins(name,email,phone,status) VALUES ('Finance CI','finance-ci@example.invalid','+256700000002','ACTIVE')",
                Map.of());
        jdbc.update(
                "INSERT INTO notification_admin_recipients(group_code,admin_id) SELECT 'FINANCE',id FROM admins WHERE email='finance-ci@example.invalid'",
                Map.of());
        tx.executeWithoutResult(
                status -> orchestrator.record(0, "ci-ledger", "ledger.imbalance", "ci-ledger"));
        long alert =
                jdbc.queryForObject(
                        "SELECT id FROM notification_events WHERE event_id='ci-ledger'",
                        Map.of(),
                        Long.class);
        tx.executeWithoutResult(status -> orchestrator.process(alert));
        worker.processDue(100);
        jdbc.update(
                "UPDATE communication_outbox SET next_attempt_at=NOW() WHERE status='PENDING'",
                Map.of());
        worker.processDue(100);
        assertEquals(
                2, second.calls.get(), "Critical platform alert must use same fake routing/outbox");
        jdbc.update(
                "INSERT INTO communication_sms_suppressions(merchant_id,phone_e164,scope,active_flag) VALUES (:merchant,'+256700000001','MARKETING','Y')",
                Map.of("merchant", merchant));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        tx.executeWithoutResult(
                                status ->
                                        merchantService.enqueueSms(
                                                merchant,
                                                "+256700000001",
                                                "Optional",
                                                "MARKETING",
                                                null,
                                                "ci-marketing",
                                                null)));
        var options =
                new MerchantCommunicationService.SmsOptions(
                        null,
                        Instant.now().plusSeconds(3600).toString(),
                        "BALANCED",
                        null,
                        "UGX",
                        false,
                        false,
                        true);
        tx.executeWithoutResult(
                status ->
                        merchantService.enqueueSms(
                                merchant,
                                "+256700000003",
                                "Scheduled",
                                "TRANSACTIONAL",
                                null,
                                "ci-scheduled",
                                null,
                                options));
        worker.processDue(100);
        assertEquals(2, second.calls.get(), "Future schedule must not dispatch early");
        var unapproved =
                new MerchantCommunicationService.SmsOptions(
                        "NotApproved", null, "BALANCED", null, "UGX", false, false, true);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        merchantService.enqueueSms(
                                merchant,
                                "+256700000003",
                                "Restricted",
                                "TRANSACTIONAL",
                                null,
                                "ci-sender",
                                null,
                                unapproved));
    }

    static final class FakeProvider implements CommunicationProviderAdapter {
        final String code;
        final boolean fails;
        final AtomicInteger calls = new AtomicInteger();

        FakeProvider(String code, boolean fails) {
            this.code = code;
            this.fails = fails;
        }

        public String providerCode() {
            return code;
        }

        public CommunicationChannel channel() {
            return CommunicationChannel.SMS;
        }

        public ProviderCapabilities capabilities() {
            return ProviderCapabilities.builder().send(true).build();
        }

        public ProviderSendResult send(ProviderSendRequest request) {
            int count = calls.incrementAndGet();
            return fails
                    ? ProviderSendResult.failed(code, "FAKE_FAILURE", "CI fake failure", "", true)
                    : ProviderSendResult.sent(code, "fake-" + count, "SENT");
        }
    }

    private NotificationMysqlScenario() {}
}
