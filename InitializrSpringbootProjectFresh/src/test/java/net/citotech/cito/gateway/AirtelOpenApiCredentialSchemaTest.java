package net.citotech.cito.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AirtelOpenApiCredentialSchemaTest {
    @Test
    void acceptsOfficialProductionCredentialShape() {
        assertDoesNotThrow(
                () ->
                        AirtelOpenApiCredentialSchema.validate(
                                credentials(AirtelOpenApiCredentialSchema.PRODUCTION_BASE_URL),
                                "PRODUCTION",
                                "UG",
                                "UGX"));
    }

    @Test
    void rejectsUatHostForProduction() {
        PaymentGatewayException error =
                assertThrows(
                        PaymentGatewayException.class,
                        () ->
                                AirtelOpenApiCredentialSchema.validate(
                                        credentials(AirtelOpenApiCredentialSchema.SANDBOX_BASE_URL),
                                        "PRODUCTION",
                                        "UG",
                                        "UGX"));
        assertTrue(error.getMessage().contains("approved origin"));
    }

    @Test
    void rejectsScopeMismatch() {
        PaymentGatewayException error =
                assertThrows(
                        PaymentGatewayException.class,
                        () ->
                                AirtelOpenApiCredentialSchema.validate(
                                        credentials(
                                                AirtelOpenApiCredentialSchema.PRODUCTION_BASE_URL),
                                        "PRODUCTION",
                                        "KE",
                                        "KES"));
        assertTrue(error.getMessage().contains("country"));
    }

    @Test
    void collectionOnlyCredentialsAreAcceptedButCannotAuthorizePayouts() {
        Map<String, Object> values = credentials(AirtelOpenApiCredentialSchema.PRODUCTION_BASE_URL);
        values.remove("apiPin");
        values.remove("publicKey");
        assertDoesNotThrow(
                () ->
                        AirtelOpenApiCredentialSchema.validateForOperation(
                                values, "PRODUCTION", "UG", "UGX", "COLLECT"));
        assertThrows(
                PaymentGatewayException.class,
                () ->
                        AirtelOpenApiCredentialSchema.validateForOperation(
                                values, "PRODUCTION", "UG", "UGX", "PAYOUT"));
    }

    private Map<String, Object> credentials(String baseUrl) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("baseUrl", baseUrl);
        values.put("clientId", "client-id");
        values.put("clientSecret", "client-secret");
        values.put("country", "UG");
        values.put("currency", "UGX");
        values.put("apiPin", "1234");
        values.put("publicKey", "-----BEGIN PUBLIC KEY-----\nZmFrZQ==\n-----END PUBLIC KEY-----");
        return values;
    }
}
