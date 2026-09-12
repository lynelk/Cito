package net.citotech.cito.platform.kernel;

import java.util.Map;
import java.util.TreeSet;
import net.citotech.cito.Common;
import net.citotech.cito.Model.MerchantUser;
import net.citotech.cito.Model.User;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared audit boundary delegating to the existing canonical audit writers, not a second writer.
 * Only bounded resource metadata and summary field names are recorded; summary values stay with
 * their owning domain. A failed legacy writer result is promoted to an exception so the surrounding
 * business transaction cannot commit without its audit record.
 */
@Service
public class PlatformAuditGateway implements PlatformAuditContract {
    private final NamedParameterJdbcTemplate jdbc;

    public PlatformAuditGateway(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void record(
            PlatformTenantContext context,
            String action,
            String resourceType,
            String resourceId,
            Map<String, Object> summary) {
        if (context == null)
            throw new IllegalArgumentException("Platform tenant context is required");
        String safeAction = buildAction(context, action, resourceType, resourceId, summary);
        String actor = context.actorId() == null ? context.actorType() : context.actorId();
        String result;
        if (context.merchantId() == null) {
            User user = new User();
            user.setName(required(context.actorType()));
            user.setEmail(required(actor));
            result = Common.recordAction(user, safeAction, jdbc);
        } else {
            MerchantUser user = new MerchantUser();
            user.setName(required(context.actorType()));
            user.setEmail(required(actor));
            user.setMerchant_id(context.merchantId());
            result = Common.recordMerchantAction(user, safeAction, jdbc);
        }
        if (!"success".equals(result)) {
            throw new IllegalStateException("Platform audit could not be recorded");
        }
    }

    private String buildAction(
            PlatformTenantContext context,
            String action,
            String resourceType,
            String resourceId,
            Map<String, Object> summary) {
        TreeSet<String> fields = new TreeSet<>();
        if (summary != null) {
            if (summary.size() > 50)
                throw new IllegalArgumentException("Too many audit summary fields");
            summary.keySet().forEach(field -> fields.add(required(field)));
        }
        return "CITO_PLATFORM action="
                + required(action)
                + " resourceType="
                + required(resourceType)
                + " resourceId="
                + optional(resourceId)
                + " environment="
                + required(context.environment())
                + " application="
                + optional(context.applicationId())
                + " correlation="
                + optional(context.correlationId())
                + " summaryFields="
                + fields;
    }

    private String required(String value) {
        String safe = optional(value);
        if (safe.isEmpty()) throw new IllegalArgumentException("Audit value is required");
        return safe;
    }

    private String optional(String value) {
        if (value == null) return "";
        if (value.length() > 256 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Audit metadata is invalid");
        }
        return value.trim();
    }
}
