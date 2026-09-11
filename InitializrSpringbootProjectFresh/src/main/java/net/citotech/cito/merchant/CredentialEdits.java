package net.citotech.cito.merchant;

import java.util.*;
import net.citotech.cito.gateway.PaymentGatewayException;

/**
 * Secret presence and editable public values are separate; masked text is never credential data.
 */
public final class CredentialEdits {
    private CredentialEdits() {}

    public static boolean publicField(String field) {
        String key = field.toLowerCase(Locale.ROOT);
        return key.endsWith("url")
                || key.endsWith("path")
                || key.endsWith("host")
                || key.endsWith("environment")
                || key.endsWith("currency")
                || key.equals("country")
                || key.equals("partyidtype")
                || key.equals("publickey");
    }

    public static Map<String, Object> merge(
            Map<String, Object> previous, Map<String, Object> changes, Collection<?> clear) {
        Map<String, Object> result = new LinkedHashMap<>(previous);
        for (var entry : changes.entrySet()) {
            String value = Objects.toString(entry.getValue(), "");
            if (value.contains("****")) {
                if (!previous.containsKey(entry.getKey()))
                    throw new PaymentGatewayException(
                            "Masked values cannot be saved as credentials");
                continue;
            }
            if (!publicField(entry.getKey())
                    && value.isBlank()
                    && previous.containsKey(entry.getKey())) continue;
            result.put(entry.getKey(), value);
        }
        if (clear != null) for (Object field : clear) result.remove(String.valueOf(field));
        return result;
    }

    public static Map<String, Object> mask(Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach(
                (key, value) ->
                        result.put(
                                key,
                                publicField(key)
                                        ? value
                                        : Objects.toString(value, "").isBlank() ? "" : "****"));
        return result;
    }
}
