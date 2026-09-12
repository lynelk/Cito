package net.citotech.cito.treasury;

import java.util.Map;
import net.citotech.cito.admin.AdminPermissionService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Actor-specific presentation policy; never authorizes or submits a payment. */
@RestController
@RequestMapping("/api/v2/admin/provider-treasury/live-tests")
@PreAuthorize("hasRole('ADMIN')")
public class ProviderLiveTestMfaController {
    private final ProviderLiveTestService service;
    private final AdminPermissionService permissions;

    public ProviderLiveTestMfaController(
            ProviderLiveTestService service, AdminPermissionService permissions) {
        this.service = service;
        this.permissions = permissions;
    }

    @GetMapping("/mfa-policy")
    public ResponseEntity<Map<String, Object>> policy(Authentication authentication) {
        permissions.require("LIVE_COLLECTION_TEST", "provider-live-test-mfa-policy", "mtn_momo");
        String actor = authentication == null ? "" : authentication.getName();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.mfaPolicy(actor));
    }
}
