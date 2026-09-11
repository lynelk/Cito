package net.citotech.cito.api.v2;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import net.citotech.cito.Common;
import net.citotech.cito.Model.Merchant;
import net.citotech.cito.security.CanonicalRequestSigner;
import net.citotech.cito.security.ReplayProtectionService;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** Security helper for /api/v2 request verification. */
@Service
public class V2RequestSecurityService {
    private final net.citotech.cito.developer.reference.ApiAccessBillingService apiBilling;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ReplayProtectionService replayProtectionService;

    public V2RequestSecurityService(
            NamedParameterJdbcTemplate jdbcTemplate,
            ReplayProtectionService replayProtectionService,
            net.citotech.cito.developer.reference.ApiAccessBillingService apiBilling) {
        this.apiBilling = apiBilling;
        this.jdbcTemplate = jdbcTemplate;
        this.replayProtectionService = replayProtectionService;
    }

    public Merchant verify(HttpServletRequest request, String body, String merchantNumber) {
        String timestamp = request.getHeader("X-CPay-Timestamp");
        String nonce = request.getHeader("X-CPay-Nonce");
        String version = request.getHeader("X-CPay-Signature-Version");
        String requestSignature = request.getHeader("X-CPay-Signature");

        if (!CanonicalRequestSigner.SIGNATURE_VERSION.equalsIgnoreCase(version)) {
            throw new V2RequestSecurityException("Unsupported or missing signature version");
        }
        if (!replayProtectionService.accept(merchantNumber, timestamp, nonce)) {
            throw new V2RequestSecurityException("Timestamp or nonce was rejected");
        }
        Merchant merchant = Common.getMerchantByAccountNumber(merchantNumber, jdbcTemplate);
        if (merchant == null) {
            throw new V2RequestSecurityException("Merchant was not found");
        }
        String canonical =
                CanonicalRequestSigner.canonicalize(
                        request.getMethod(),
                        request.getRequestURI(),
                        canonicalQuery(request),
                        timestamp,
                        nonce,
                        body);
        if (!CanonicalRequestSigner.verify(merchant, canonical, requestSignature)) {
            throw new V2RequestSecurityException("Invalid request signature");
        }
        apiBilling.admitted(merchant.getId(), null, apiBilling.signedEnvironment(request, body));
        return merchant;
    }

    private String canonicalQuery(HttpServletRequest request) {
        Map<String, String[]> parameters = request.getParameterMap();
        if (parameters == null || parameters.isEmpty()) {
            return "";
        }
        return parameters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(
                        entry ->
                                Arrays.stream(entry.getValue())
                                        .sorted()
                                        .map(value -> encode(entry.getKey()) + "=" + encode(value)))
                .collect(Collectors.joining("&"));
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
