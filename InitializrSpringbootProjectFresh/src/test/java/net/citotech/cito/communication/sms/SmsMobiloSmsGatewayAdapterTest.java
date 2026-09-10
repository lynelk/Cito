package net.citotech.cito.communication.sms;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.citotech.cito.Model.HttpRequestResponse;
import net.citotech.cito.Model.SmsDeliveryStatus;
import net.citotech.cito.communication.credentials.CommunicationCredentialStore;
import org.junit.jupiter.api.Test;

class SmsMobiloSmsGatewayAdapterTest {
    private final SmsMobiloSmsGatewayAdapter adapter =
            new SmsMobiloSmsGatewayAdapter(
                    mock(CommunicationCredentialStore.class), new ObjectMapper());

    @Test
    void officialSendContractUsesFixedFields() {
        assertThat(
                        SmsMobiloSmsGatewayAdapter.payload(
                                new SmsSendRequest(
                                        1, 42, "Hello", "+256700000001", "SMSMOBILO_SMS", "Cito")))
                .containsEntry("to", "+256700000001")
                .containsEntry("from", "Cito")
                .containsEntry("message", "Hello")
                .hasSize(3);
    }

    @Test
    void rejectsMultipleRecipientsToPreserveOneMessageOneReceipt() {
        assertThatThrownBy(
                        () ->
                                SmsMobiloSmsGatewayAdapter.payload(
                                        new SmsSendRequest(
                                                1,
                                                42,
                                                "Hello",
                                                "+256700000001,+256700000002",
                                                "SMSMOBILO_SMS",
                                                null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void capturesOfficialMessageIdWithoutClaimingDelivery() {
        var result =
                adapter.normalize(
                        response(
                                200,
                                "{\"status\":\"ok\",\"data\":{\"message_id\":4821,\"status\":\"queued\",\"credits_used\":1}}"));
        assertThat(result.status()).isEqualTo(SmsDeliveryStatus.SENT);
        assertThat(result.providerMessageId()).isEqualTo("4821");
    }

    @Test
    void malformedSuccessIsUnknownAndMustNotTriggerBlindFallback() {
        assertThat(adapter.normalize(response(200, "broken")).status())
                .isEqualTo(SmsDeliveryStatus.UNKNOWN);
        assertThat(adapter.normalize(response(200, "{\"status\":\"ok\"}")).status())
                .isEqualTo(SmsDeliveryStatus.UNKNOWN);
        assertThat(adapter.normalize(response(504, "")).status())
                .isEqualTo(SmsDeliveryStatus.UNKNOWN);
    }

    @Test
    void rateLimitIsRetryableButAuthenticationRejectionIsTerminal() {
        assertThat(adapter.normalize(response(429, "")).status())
                .isEqualTo(SmsDeliveryStatus.FAILED);
        assertThat(adapter.normalize(response(401, "secret provider body")).status())
                .isEqualTo(SmsDeliveryStatus.REJECTED);
        assertThat(adapter.normalize(response(401, "secret provider body")).gwResponse()).isEmpty();
    }

    private HttpRequestResponse response(int status, String body) {
        var response = new HttpRequestResponse();
        response.setStatusCode(status);
        response.setResponse(body);
        return response;
    }
}
