package net.citotech.cito.gateway;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AirtelStatusClientTest {
    static Map<String, String> credentials() {
        return new LinkedHashMap<>(
                Map.of(
                        "baseUrl",
                        "https://openapiuat.airtel.africa",
                        "clientId",
                        "test-client",
                        "clientSecret",
                        "test-secret",
                        "country",
                        "UG",
                        "currency",
                        "UGX"));
    }

    static String response(String status) {
        return "{\"status\":{\"success\":true},\"data\":{\"transaction\":{\"id\":\"original-ref\",\"status\":\""
                + status
                + "\",\"airtel_money_id\":\"provider-receipt\"}}}";
    }

    @ParameterizedTest
    @ValueSource(strings = {"TS", "SUCCESSFUL", "COMPLETED"})
    void acceptsOnlyVerifiedTerminalSuccess(String status) {
        assertThat(
                        AirtelStatusClient.parse(
                                        response(status),
                                        "original-ref",
                                        new BigDecimal("100"),
                                        "UGX")
                                .status())
                .isEqualTo("SUCCESSFUL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"TF", "FAILED", "REJECTED"})
    void acceptsTerminalFailure(String status) {
        assertThat(
                        AirtelStatusClient.parse(
                                        response(status),
                                        "original-ref",
                                        new BigDecimal("100"),
                                        "UGX")
                                .status())
                .isEqualTo("FAILED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"TIP", "PENDING", "UNKNOWN", ""})
    void unknownAndPendingNeverSettle(String status) {
        assertThat(
                        AirtelStatusClient.parse(
                                        response(status),
                                        "original-ref",
                                        new BigDecimal("100"),
                                        "UGX")
                                .terminal())
                .isFalse();
    }

    @Test
    void rejectsWrongReference() {
        assertThatThrownBy(
                        () ->
                                AirtelStatusClient.parse(
                                        response("TS"), "other-ref", BigDecimal.TEN, "UGX"))
                .hasMessage("AIRTEL_STATUS_REFERENCE_MISMATCH");
    }

    @Test
    void rejectsWrongAmount() {
        JSONObject b = new JSONObject(response("TS"));
        b.getJSONObject("data").getJSONObject("transaction").put("amount", "101");
        assertThatThrownBy(
                        () ->
                                AirtelStatusClient.parse(
                                        b.toString(), "original-ref", new BigDecimal("100"), "UGX"))
                .hasMessage("AIRTEL_STATUS_AMOUNT_MISMATCH");
    }

    @Test
    void rejectsWrongCurrency() {
        JSONObject b = new JSONObject(response("TS"));
        b.getJSONObject("data").getJSONObject("transaction").put("currency", "KES");
        assertThatThrownBy(
                        () ->
                                AirtelStatusClient.parse(
                                        b.toString(), "original-ref", BigDecimal.TEN, "UGX"))
                .hasMessage("AIRTEL_STATUS_CURRENCY_MISMATCH");
    }

    @Test
    void rejectsContradictoryError() {
        assertThatThrownBy(
                        () ->
                                AirtelStatusClient.parse(
                                        new JSONObject(response("TS"))
                                                .put("error", "declined")
                                                .toString(),
                                        "original-ref",
                                        BigDecimal.TEN,
                                        "UGX"))
                .hasMessage("AIRTEL_STATUS_CONFLICTING_ERROR");
    }

    @Test
    void rejectsFailedEnvelope() {
        assertThatThrownBy(
                        () ->
                                AirtelStatusClient.parse(
                                        response("TS").replace("true", "false"),
                                        "original-ref",
                                        BigDecimal.TEN,
                                        "UGX"))
                .hasMessage("AIRTEL_STATUS_ENVELOPE_REJECTED");
    }

    @Test
    void rejectsMalformedBody() {
        assertThatThrownBy(
                        () ->
                                AirtelStatusClient.parse(
                                        "not JSON", "original-ref", BigDecimal.TEN, "UGX"))
                .hasMessage("AIRTEL_STATUS_INVALID_RESPONSE");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://untrusted.example/auth",
                "//untrusted.example/auth",
                "http://openapiuat.airtel.africa/auth",
                "https://user@openapiuat.airtel.africa/auth",
                "https://openapiuat.airtel.africa:444/auth",
                "/auth?secret=x"
            })
    void rejectsCredentialExfiltrationEndpoints(String path) {
        assertThatThrownBy(() -> AirtelStatusClient.endpoint(credentials(), path))
                .isInstanceOf(PaymentGatewayException.class);
    }

    @Test
    void refreshesOnceAndUsesCorrectProductStatusEndpoint() {
        ProviderTokenStoreService tokens = mock(ProviderTokenStoreService.class);
        when(tokens.findValid(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        AirtelStatusClient client = spy(new AirtelStatusClient(tokens));
        doReturn(
                        new AirtelStatusClient.Reply(
                                200, "{\"access_token\":\"test-token\",\"expires_in\":60}"))
                .when(client)
                .exchange(eq("POST"), any(URI.class), anyString(), anyMap());
        doReturn(
                        new AirtelStatusClient.Reply(401, ""),
                        new AirtelStatusClient.Reply(200, response("TS")))
                .when(client)
                .exchange(eq("GET"), any(URI.class), anyString(), anyMap());
        assertThat(
                        client.verify(
                                        "PAYOUT",
                                        "original-ref",
                                        "SANDBOX",
                                        "UG",
                                        "UGX",
                                        BigDecimal.TEN,
                                        credentials())
                                .status())
                .isEqualTo("SUCCESSFUL");
        verify(client, times(2))
                .exchange(
                        eq("GET"),
                        eq(
                                URI.create(
                                        "https://openapiuat.airtel.africa/standard/v2/disbursements/original-ref/")),
                        eq(""),
                        anyMap());
        verify(client, times(2)).exchange(eq("POST"), any(URI.class), anyString(), anyMap());
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 429, 500, 503})
    void readErrorsRemainUncertain(int code) {
        ProviderTokenStoreService tokens = mock(ProviderTokenStoreService.class);
        ProviderToken token = new ProviderToken();
        token.setTokenValue("test-token");
        when(tokens.findValid(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(token));
        AirtelStatusClient client = spy(new AirtelStatusClient(tokens));
        doReturn(new AirtelStatusClient.Reply(code, "{}"))
                .when(client)
                .exchange(eq("GET"), any(), anyString(), anyMap());
        assertThatThrownBy(
                        () ->
                                client.verify(
                                        "COLLECT",
                                        "original-ref",
                                        "SANDBOX",
                                        "UG",
                                        "UGX",
                                        BigDecimal.TEN,
                                        credentials()))
                .hasMessage("AIRTEL_STATUS_HTTP_" + code);
        verify(client, never()).exchange(eq("POST"), any(), anyString(), anyMap());
    }
}
