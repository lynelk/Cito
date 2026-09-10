package net.citotech.cito.gateway;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.citotech.cito.Common;
import net.citotech.cito.Model.HttpRequestResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

/**
 * Spring-managed outbound HTTP executor for legacy provider calls (audit C4).
 *
 * <p>The old payment gateways still call {@link Common#doHttpRequest(String, String, String, Map)}
 * from static legacy code. This bridge keeps that call site stable while moving the actual
 * transport to Spring's {@link RestClient}, with configurable timeouts, standard JVM TLS
 * validation, and Micrometer timing around provider/webhook traffic.
 */
@Component
public class RestClientOutboundHttpExecutor implements Common.OutboundHttpExecutor {
    private final RestClient restClient;
    private final MeterRegistry meterRegistry;

    public RestClientOutboundHttpExecutor(
            ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${cpay.http.connect-timeout-ms:30000}") int connectTimeoutMs,
            @Value("${cpay.http.read-timeout-ms:60000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory() {
                    @Override
                    protected void prepareConnection(
                            java.net.HttpURLConnection connection, String method)
                            throws java.io.IOException {
                        super.prepareConnection(connection, method);
                        connection.setInstanceFollowRedirects(false);
                    }
                };
        requestFactory.setConnectTimeout(Duration.ofMillis(Math.max(1, connectTimeoutMs)));
        requestFactory.setReadTimeout(Duration.ofMillis(Math.max(1, readTimeoutMs)));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    @PostConstruct
    public void register() {
        Common.setOutboundHttpExecutor(this);
    }

    @Override
    public HttpRequestResponse execute(
            String method, String url, String data, Map<String, String> headers) {
        String normalizedMethod = method == null ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        Map<String, String> requestHeaders = headers == null ? Map.of() : new HashMap<>(headers);
        long startedNanos = System.nanoTime();
        HttpRequestResponse trace = baseResponse(url, data, requestHeaders);
        int status = 0;
        try {
            HttpMethod httpMethod = HttpMethod.valueOf(normalizedMethod);
            RestClient.RequestBodySpec spec = restClient.method(httpMethod).uri(url);
            spec.headers(httpHeaders -> requestHeaders.forEach(httpHeaders::set));

            RestClient.RequestHeadersSpec<?> request =
                    hasBody(httpMethod) ? spec.body(data == null ? "" : data) : spec;
            ResponseEntity<String> entity =
                    request.exchange(
                            (clientRequest, clientResponse) ->
                                    ResponseEntity.status(clientResponse.getStatusCode())
                                            .headers(clientResponse.getHeaders())
                                            .body(
                                                    StreamUtils.copyToString(
                                                            clientResponse.getBody(),
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8)));

            status = entity.getStatusCode().value();
            trace.setStatusCode(status);
            trace.setResponse(entity.getBody() == null ? "" : entity.getBody());
            trace.setResponseHeaders(flattenHeaders(entity));
            trace.setErrorMessage("");
            return trace;
        } catch (Exception ex) {
            trace.setStatusCode(status);
            trace.setResponse("");
            trace.setErrorMessage(
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            return trace;
        } finally {
            recordMetric(normalizedMethod, url, status, startedNanos);
        }
    }

    private HttpRequestResponse baseResponse(String url, String data, Map<String, String> headers) {
        HttpRequestResponse response = new HttpRequestResponse();
        response.setUrl(url);
        response.setRequestData(data);
        response.setRequestHeaders(headers);
        return response;
    }

    private boolean hasBody(HttpMethod method) {
        return HttpMethod.POST.equals(method)
                || HttpMethod.PUT.equals(method)
                || HttpMethod.PATCH.equals(method);
    }

    private Map<String, String> flattenHeaders(ResponseEntity<String> entity) {
        Map<String, String> responseHeaders = new HashMap<>();
        entity.getHeaders().forEach((key, values) -> responseHeaders.put(key, join(values)));
        return responseHeaders;
    }

    private String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join("", values);
    }

    private void recordMetric(String method, String url, int status, long startedNanos) {
        if (meterRegistry == null) {
            return;
        }
        Timer.builder("cpay.outbound.http")
                .description("Outbound HTTP calls made by CPay provider and callback clients")
                .tag("method", method)
                .tag("host", safeHost(url))
                .tag("status", String.valueOf(status))
                .register(meterRegistry)
                .record(Duration.ofNanos(System.nanoTime() - startedNanos));
    }

    private String safeHost(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception ignored) {
            return "unknown";
        }
    }
}
