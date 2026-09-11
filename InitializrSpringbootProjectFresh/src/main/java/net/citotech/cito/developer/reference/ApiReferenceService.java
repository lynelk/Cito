package net.citotech.cito.developer.reference;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class ApiReferenceService {
    private final Map<String, Object> document;
    private final ApiAccessBillingService billing;

    @SuppressWarnings("unchecked")
    public ApiReferenceService(ObjectMapper mapper, ApiAccessBillingService billing)
            throws IOException {
        try (var input =
                new ClassPathResource("api-reference/merchant-openapi.json").getInputStream()) {
            this.document = mapper.readValue(input, Map.class);
        }
        this.billing = billing;
    }

    public Map<String, Object> document() {
        return document;
    }

    public String guide() throws IOException {
        return new ClassPathResource("api-reference/integration-guide.md")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public boolean contains(String method, String path) {
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        Object item = paths.get(path);
        return item instanceof Map<?, ?> operations
                && operations.containsKey(method.toLowerCase(java.util.Locale.ROOT));
    }

    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @SuppressWarnings("unchecked")
    public void registerEndpoints() {
        Map<String, Map<String, Object>> paths =
                (Map<String, Map<String, Object>>) document.get("paths");
        paths.forEach(
                (path, operations) ->
                        operations
                                .keySet()
                                .forEach(
                                        method ->
                                                billing.ensureRate(
                                                        method.toUpperCase(java.util.Locale.ROOT),
                                                        path)));
    }

    @SuppressWarnings("unchecked")
    public boolean isWorkspace(String method, String path) {
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        if (!(paths.get(path) instanceof Map<?, ?> item)) return false;
        Object operation = item.get(method.toLowerCase(java.util.Locale.ROOT));
        return operation instanceof Map<?, ?> op
                && "MERCHANT_WORKSPACE".equals(op.get("x-cito-audience"));
    }

    public List<Map<String, Object>> merchantRates() {
        return billing.rates().stream()
                .filter(
                        row ->
                                contains(
                                        row.get("http_method").toString(),
                                        row.get("route_template").toString()))
                .toList();
    }
}
