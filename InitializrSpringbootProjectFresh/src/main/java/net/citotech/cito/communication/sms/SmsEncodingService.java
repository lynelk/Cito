package net.citotech.cito.communication.sms;

import org.springframework.stereotype.Service;

/**
 * Calculates billable SMS segments using GSM 03.38 rules rather than Java character count.
 * GSM extension-table characters consume two septets; any non-GSM character switches the whole
 * message to UCS-2/UTF-16 segmentation (70 units for one segment, 67 for concatenated segments).
 */
@Service
public class SmsEncodingService {

    private static final String GSM_BASIC =
            "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ"
                    + " !\"#¤%&'()*+,-./0123456789:;<=>?¡"
                    + "ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿"
                    + "abcdefghijklmnopqrstuvwxyzäöñüà";

    private static final String GSM_EXTENDED = "^{}\\[~]|€\f";

    public SmsAnalysis analyze(String message) {
        String body = message == null ? "" : message;
        boolean gsm7 = true;
        int units = 0;

        for (int offset = 0; offset < body.length(); ) {
            int codePoint = body.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint > Character.MAX_VALUE) {
                gsm7 = false;
                break;
            }
            char value = (char) codePoint;
            if (GSM_BASIC.indexOf(value) >= 0) {
                units += 1;
            } else if (GSM_EXTENDED.indexOf(value) >= 0) {
                units += 2;
            } else {
                gsm7 = false;
                break;
            }
        }

        if (!gsm7) {
            units = body.length(); // UTF-16 code units, including surrogate pairs.
        }

        int singleLimit = gsm7 ? 160 : 70;
        int concatenatedLimit = gsm7 ? 153 : 67;
        int segments;
        int remaining;
        if (units == 0) {
            segments = 0;
            remaining = singleLimit;
        } else if (units <= singleLimit) {
            segments = 1;
            remaining = singleLimit - units;
        } else {
            segments = (int) Math.ceil(units / (double) concatenatedLimit);
            int usedInLast = units % concatenatedLimit;
            remaining = usedInLast == 0 ? 0 : concatenatedLimit - usedInLast;
        }

        return new SmsAnalysis(
                gsm7 ? "GSM-7" : "UCS-2",
                body.length(),
                units,
                segments,
                segments <= 1 ? singleLimit : concatenatedLimit,
                remaining,
                segments > 10);
    }

    public record SmsAnalysis(
            String encoding,
            int characters,
            int encodingUnits,
            int segments,
            int segmentLimit,
            int remainingInCurrentSegment,
            boolean longMessageWarning) {}
}
