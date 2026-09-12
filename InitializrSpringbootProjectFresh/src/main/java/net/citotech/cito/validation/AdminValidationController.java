package net.citotech.cito.validation;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import net.citotech.cito.experience.ExperienceAccessContext;
import net.citotech.cito.gateway.PaymentGatewayException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/admin/validation")
public class AdminValidationController {
    private final CitoValidateService validation;
    private final ExperienceAccessContext accessContext;

    public AdminValidationController(
            CitoValidateService validation, ExperienceAccessContext accessContext) {
        this.validation = validation;
        this.accessContext = accessContext;
    }

    @GetMapping("/rule-packs")
    public ResponseEntity<?> rulePacks(
            HttpServletRequest request, Authentication authentication) {
        var access = accessContext.require(request, authentication);
        accessContext.requireAdmin(access);
        return ResponseEntity.ok(validation.allRulePacks());
    }

    @PostMapping("/rule-pack-versions")
    public ResponseEntity<?> createVersion(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request,
            Authentication authentication) {
        var access = accessContext.require(request, authentication);
        accessContext.requireAdmin(access);
        try {
            return ResponseEntity.ok(
                    validation.createRulePackVersion(
                            text(body.get("packCode")),
                            text(body.get("versionNumber")),
                            text(body.get("rulesJson")),
                            access.actor()));
        } catch (PaymentGatewayException e) {
            return bad("VALIDATION_RULE_VERSION_REJECTED", e.getMessage());
        }
    }

    @PostMapping("/rule-pack-versions/approve")
    public ResponseEntity<?> approveVersion(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request,
            Authentication authentication) {
        var access = accessContext.require(request, authentication);
        accessContext.requireAdmin(access);
        try {
            return ResponseEntity.ok(
                    validation.approveRulePackVersion(
                            text(body.get("packCode")),
                            text(body.get("versionNumber")),
                            access.actor()));
        } catch (PaymentGatewayException e) {
            return bad("VALIDATION_RULE_APPROVAL_REJECTED", e.getMessage());
        }
    }

    private ResponseEntity<?> bad(String code, String message) {
        return ResponseEntity.badRequest().body(Map.of("code", code, "message", message));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
