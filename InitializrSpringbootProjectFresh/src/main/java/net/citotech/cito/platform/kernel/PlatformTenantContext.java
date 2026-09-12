package net.citotech.cito.platform.kernel;

import java.util.Set;

/**
 * Canonical execution identity passed across Cito domain boundaries.
 *
 * <p>The context is resolved server-side after authentication. Domain commands may add their own
 * subject/resource IDs, but they must not invent another tenant, environment, actor or correlation
 * model.
 */
public record PlatformTenantContext(
        Long organizationId,
        Long merchantId,
        String actorId,
        String actorType,
        Set<String> roles,
        String environment,
        String applicationId,
        String correlationId) {

    public PlatformTenantContext {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public boolean platformScoped() {
        return merchantId == null;
    }
}
