package net.citotech.cito.validation;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import net.citotech.cito.gateway.PaymentGatewayException;
import net.citotech.cito.platform.MerchantSessionContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/merchant-self-service/validation")
public class MerchantValidationController {
    private final CitoValidateService validation;
    private final MerchantSessionContext session;

    public MerchantValidationController(
            CitoValidateService validation, MerchantSessionContext session) {
        this.validation = validation;
        this.session = session;
    }

    @GetMapping("/rule-packs")
    public ResponseEntity<?> rulePacks(HttpServletRequest request) {
        session.requireUser(request);
        return ResponseEntity.ok(validation.availableRulePacks());
    }

    @PostMapping("/jobs")
    public ResponseEntity<?> createJob(
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(
                    validation.createJob(
                            session.requireMerchantId(request),
                            text(body.get("environment")),
                            text(body.get("packCode")),
                            text(body.get("versionNumber")),
                            text(body.get("mappingCode")),
                            text(body.get("mappingVersion")),
                            text(body.get("sourceReference")),
                            text(body.get("sourcePayload")),
                            text(body.get("idempotencyKey")),
                            session.actor(request)));
        } catch (PaymentGatewayException e) {
            return bad("VALIDATION_JOB_REJECTED", e.getMessage());
        }
    }

    @GetMapping("/jobs")
    public ResponseEntity<?> jobs(
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            HttpServletRequest request) {
        return ResponseEntity.ok(validation.jobs(session.requireMerchantId(request), limit));
    }

    @GetMapping("/jobs/{jobReference}")
    public ResponseEntity<?> job(
            @PathVariable String jobReference, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(
                    validation.job(session.requireMerchantId(request), jobReference));
        } catch (PaymentGatewayException e) {
            return bad("VALIDATION_JOB_NOT_FOUND", e.getMessage());
        }
    }

    @GetMapping("/jobs/{jobReference}/findings")
    public ResponseEntity<?> findings(
            @PathVariable String jobReference,
            @RequestParam(value = "limit", defaultValue = "250") int limit,
            HttpServletRequest request) {
        try {
            return ResponseEntity.ok(
                    validation.findings(
                            session.requireMerchantId(request), jobReference, limit));
        } catch (PaymentGatewayException e) {
            return bad("VALIDATION_JOB_NOT_FOUND", e.getMessage());
        }
    }

    private ResponseEntity<?> bad(String code, String message) {
        return ResponseEntity.badRequest().body(Map.of("code", code, "message", message));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
