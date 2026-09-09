package net.citotech.cito.communication.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SmsEncodingServiceTest {

    private final SmsEncodingService service = new SmsEncodingService();

    @Test
    void countsSingleGsmMessage() {
        var result = service.analyze("Payment received");
        assertEquals("GSM-7", result.encoding());
        assertEquals(1, result.segments());
        assertEquals(160 - 16, result.remainingInCurrentSegment());
    }

    @Test
    void gsmExtensionCharactersConsumeTwoSeptets() {
        var result = service.analyze("{}[]^~|\\€");
        assertEquals("GSM-7", result.encoding());
        assertEquals(18, result.encodingUnits());
        assertEquals(1, result.segments());
    }

    @Test
    void concatenatedGsmUses153Septets() {
        var result = service.analyze("a".repeat(161));
        assertEquals("GSM-7", result.encoding());
        assertEquals(2, result.segments());
        assertEquals(145, result.remainingInCurrentSegment());
    }

    @Test
    void unicodeSwitchesWholeMessageToUcs2() {
        var result = service.analyze("Hello 😀");
        assertEquals("UCS-2", result.encoding());
        assertEquals(1, result.segments());
        assertEquals(62, result.remainingInCurrentSegment());
    }

    @Test
    void concatenatedUnicodeUses67Utf16Units() {
        var result = service.analyze("界".repeat(71));
        assertEquals("UCS-2", result.encoding());
        assertEquals(2, result.segments());
        assertEquals(63, result.remainingInCurrentSegment());
    }

    @Test
    void warnsForVeryLongMessages() {
        var result = service.analyze("a".repeat(1531));
        assertTrue(result.longMessageWarning());
        assertTrue(result.segments() > 10);
        assertFalse(service.analyze("short").longMessageWarning());
    }
}
