package net.citotech.cito.sharedprovider;

import java.util.List;
import java.util.Map;
import net.citotech.cito.admin.AdminPermissionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrative surface for CPay-sponsored provider credentials and merchant entitlements. */
@RestController
@RequestMapping("/api/v2/admin/shared-provider")
@PreAuthorize("hasRole('ADMIN')")
public class SharedProviderAdminController {
    private final SharedProviderAccessService service;
    private final AdminPermissionService permissions;

    public SharedProviderAdminController(
            SharedProviderAccessService service, AdminPermissionService permissions) {
        this.service = service;
        this.permissions = permissions;
    }

    @GetMapping("/entitlements")
    public List<Map<String, Object>> entitlements() {
        permissions.require("SHARED_PAYMENT_ENTITLEMENT_MANAGE", "shared-entitlement-list", "all");
        return service.listEntitlements();
    }

    @PostMapping("/entitlements")
    public Map<String, Object> requestEntitlement(
            @RequestBody Map<String, Object> body, Authentication authentication) {
        permissions.require(
                "SHARED_PAYMENT_ENTITLEMENT_MANAGE", "shared-entitlement-request", "merchant");
        return service.requestEntitlement(body, actor(authentication));
    }

    @PostMapping("/entitlements/{id}/approve")
    public Map<String, Object> approveEntitlement(
            @PathVariable long id, Authentication authentication) {
        permissions.require(
                "SHARED_PAYMENT_LIMIT_APPROVE", "shared-entitlement-approve", "entitlement:" + id);
        return service.approveEntitlement(id, actor(authentication));
    }

    @PostMapping("/entitlements/{id}/reject")
    public Map<String, Object> rejectEntitlement(
            @PathVariable long id, Authentication authentication) {
        permissions.require(
                "SHARED_PAYMENT_LIMIT_APPROVE", "shared-entitlement-reject", "entitlement:" + id);
        return service.rejectEntitlement(id, actor(authentication));
    }

    @PostMapping("/entitlements/{id}/disable")
    public Map<String, Object> disableEntitlement(
            @PathVariable long id, Authentication authentication) {
        permissions.require(
                "SHARED_PAYMENT_ENTITLEMENT_MANAGE",
                "shared-entitlement-disable",
                "entitlement:" + id);
        return service.disableEntitlement(id, actor(authentication));
    }

    @GetMapping("/credentials")
    public List<Map<String, Object>> credentials() {
        permissions.require("PROVIDER_CREDENTIAL_MANAGE", "provider-credential-list", "all");
        return service.listPlatformCredentials();
    }

    @PostMapping("/credentials")
    public Map<String, Object> saveCredential(
            @RequestBody Map<String, Object> body, Authentication authentication) {
        permissions.require("PROVIDER_CREDENTIAL_MANAGE", "provider-credential-save", "scope");
        return service.savePlatformCredential(body, actor(authentication));
    }

    @PostMapping("/credentials/{id}/verify")
    public Map<String, Object> verifyCredential(
            @PathVariable long id, Authentication authentication) {
        permissions.require(
                "PROVIDER_CREDENTIAL_MANAGE", "provider-credential-verify", "credential:" + id);
        return service.verifyPlatformCredential(id, actor(authentication));
    }

    @PostMapping("/credentials/{id}/approve")
    public Map<String, Object> approveCredential(
            @PathVariable long id, Authentication authentication) {
        permissions.require(
                "PROVIDER_CREDENTIAL_APPROVE", "provider-credential-approve", "credential:" + id);
        return service.approvePlatformCredential(id, actor(authentication));
    }

    @PostMapping("/credentials/{id}/disable")
    public Map<String, Object> disableCredential(
            @PathVariable long id, Authentication authentication) {
        permissions.require(
                "PROVIDER_CREDENTIAL_MANAGE", "provider-credential-disable", "credential:" + id);
        return service.disablePlatformCredential(id, actor(authentication));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private net.citotech.cito.merchant.MerchantChannelCredentialService merchantCredentials;

    @GetMapping("/merchant-credentials")
    public List<Map<String, Object>> merchantCredentials() {
        permissions.require("PROVIDER_CREDENTIAL_MANAGE", "merchant-credential-list", "all");
        return merchantCredentials.approvalQueue();
    }

    @PostMapping("/merchant-credentials/{id}/decision")
    public Map<String, Object> decideMerchantCredential(
            @PathVariable long id,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {
        String decision = String.valueOf(body.get("decision"));
        permissions.require(
                "DISABLED".equals(decision)
                        ? "PROVIDER_CREDENTIAL_MANAGE"
                        : "PROVIDER_CREDENTIAL_APPROVE",
                "merchant-credential-decision",
                "credential:" + id);
        return merchantCredentials.decide(
                id,
                ((Number) body.get("revision")).longValue(),
                decision,
                actor(authentication),
                String.valueOf(body.getOrDefault("reason", "")));
    }

    private String actor(Authentication authentication) {
        return authentication == null ? "" : authentication.getName();
    }
}
