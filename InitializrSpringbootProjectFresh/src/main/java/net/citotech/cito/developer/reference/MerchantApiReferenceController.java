package net.citotech.cito.developer.reference;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import net.citotech.cito.platform.MerchantSessionContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/portal/api-reference")
public class MerchantApiReferenceController {
    private final ApiReferenceService reference;
    private final MerchantSessionContext sessions;

    public MerchantApiReferenceController(
            ApiReferenceService reference, MerchantSessionContext sessions) {
        this.reference = reference;
        this.sessions = sessions;
    }

    @GetMapping("/openapi")
    public ResponseEntity<Map<String, Object>> openapi(HttpServletRequest request) {
        sessions.requireMerchantId(request);
        return ResponseEntity.ok()
                .header("Cache-Control", "private, no-store")
                .body(reference.document());
    }

    @GetMapping(value = "/guide", produces = "text/plain")
    public ResponseEntity<String> guide(HttpServletRequest request) throws IOException {
        sessions.requireMerchantId(request);
        return ResponseEntity.ok()
                .header("Cache-Control", "private, no-store")
                .body(reference.guide());
    }

    @GetMapping("/rates")
    public List<Map<String, Object>> rates(HttpServletRequest request) {
        sessions.requireMerchantId(request);
        return reference.merchantRates();
    }
}
