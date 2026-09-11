package net.citotech.cito.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** No network, provider calls, credentials or database writes are required by these tests. */
class GnuGridCallbackSafetyTest {

    private final GnuGridConnector connector = new GnuGridConnector("", "test-outbound-key");

    @Test
    void advertisesOnlyImplementedSynchronousCapability() {
        assertTrue(connector.supportsSync());
        assertFalse(connector.supportsAsync());
        assertTrue(connector.supports("NIN", "UG"));
    }

    @Test
    void rejectsAbsentAndForgedCallbackHeaders() {
        assertFalse(connector.validateCallbackHeaders(null));
        assertFalse(connector.validateCallbackHeaders(Map.of()));
        assertFalse(connector.validateCallbackHeaders(Map.of("X-Gnugrid-Signature", "")));
        assertFalse(connector.validateCallbackHeaders(Map.of("X-Gnugrid-Signature", "forged")));
        assertFalse(connector.validateCallbackHeaders(
                Map.of("X-Gnugrid-Signature", "test-outbound-key")));
        assertFalse(connector.validateCallbackHeaders(
                Map.of("x-gnugrid-signature", "forged", "Authorization", "Bearer test-outbound-key")));
    }

    @Test
    void directParsingCannotBypassCallbackAuthentication() {
        for (String body : new String[] {null, "", "not-json",
                "{\"reference\":\"IDV-test\",\"verified\":true}",
                "{\"reference\":\"IDV-test\",\"verified\":false}"}) {
            assertThrows(IdentityVerificationException.class,
                    () -> connector.parseCallback(body, Map.of("X-Gnugrid-Signature", "forged")));
        }
    }

    @Test
    void controllerRejectsCallbackWithoutTouchingIdentityRecords() {
        IdentityVerificationService service = mock(IdentityVerificationService.class);
        IdentityVerificationController controller =
                new IdentityVerificationController(service, List.of(connector));
        for (String signature : List.of("", "forged", "test-outbound-key")) {
            var response = controller.gnugridCallback(
                    "{\"reference\":\"IDV-test\",\"verified\":true}", signature);
            assertEquals(401, response.getStatusCode().value());
            assertTrue(response.getBody() instanceof Map<?, ?>);
            assertEquals("INVALID_CALLBACK_SIGNATURE",
                    ((Map<?, ?>) response.getBody()).get("code"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void synchronousSandboxMatchRemainsAvailable() {
        var request = new IdentityRecords.IdentityVerificationRequest(
                "IDV-test-match", 1L, "NIN", "UG", "SYNTHETIC00012", "Test Subject", "");
        var result = connector.verify(request);
        assertTrue(result.match());
        assertEquals("sandbox-IDV-test-match", result.providerReference());
    }

    @Test
    void synchronousSandboxFailureRemainsAvailable() {
        var request = new IdentityRecords.IdentityVerificationRequest(
                "IDV-test-fail", 1L, "NIN", "UG", "SYNTHETIC00011", "Test Subject", "");
        assertFalse(connector.verify(request).match());
    }
}
