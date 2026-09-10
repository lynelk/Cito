package net.citotech.cito.communication;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Executes external bulk SMS submissions atomically across all recipients. */
@Service
public class MerchantSmsBulkService {

    private final MerchantCommunicationService communicationService;

    public MerchantSmsBulkService(MerchantCommunicationService communicationService) {
        this.communicationService = communicationService;
    }

    @Transactional
    public List<Map<String, Object>> enqueue(
            long merchantId,
            List<String> recipients,
            String content,
            String purpose,
            String externalReference,
            String idempotencyBase,
            Integer expiresInSeconds,
            MerchantCommunicationService.SmsOptions options) {
        if (recipients == null || recipients.isEmpty()) {
            throw new IllegalArgumentException("recipients is required.");
        }
        if (recipients.size() > 1000) {
            throw new IllegalArgumentException(
                    "A bulk request can contain at most 1,000 recipients.");
        }
        List<Map<String, Object>> accepted = new ArrayList<>(recipients.size());
        for (int i = 0; i < recipients.size(); i++) {
            accepted.add(
                    communicationService.enqueueSms(
                            merchantId,
                            recipients.get(i),
                            content,
                            purpose,
                            indexed(externalReference, i),
                            indexed(idempotencyBase, i),
                            expiresInSeconds,
                            options));
        }
        return List.copyOf(accepted);
    }

    private String indexed(String base, int index) {
        if (base == null || base.isBlank()) return null;
        String value = base.trim() + ":" + index;
        return value.length() <= 128 ? value : value.substring(0, 128);
    }
}
