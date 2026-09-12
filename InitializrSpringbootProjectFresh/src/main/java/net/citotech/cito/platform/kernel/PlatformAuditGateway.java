package net.citotech.cito.platform.kernel;

import java.util.Map;
import java.util.TreeSet;
import net.citotech.cito.Common;
import net.citotech.cito.audit.AuditChainService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Canonical platform audit writer backed by Cito's existing append-only hash-chained audit tables.
 *
 * <p>Only resource identity, correlation and summary field names are written here. Domain-specific
 * evidence remains in its owning store so this shared audit path never becomes a secret/PII dump.
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
        if (context == null) throw new IllegalArgumentException("Platform tenant context is required");
        String safeAction =
                buildAction(context, required(action), required(resourceType), resourceId, summary);
        String actor = context.actorId() == null ? context.actorType() : context.actorId();
        if (context.merchantId() == null) {
            insertPlatform(actor, context.actorType(), safeAction);
        } else {
            insertMerchant(context.merchantId(), actor, context.actorType(), safeAction);
        }
    }

    private void insertPlatform(String actor, String actorType, String action) {
        String previous = AuditChainService.fetchLastHash(Common.DB_TABLE_AUDIT_TRAIL, jdbc);
        String hash = AuditChainService.computeEntryHash(previous, actorType, actor, null, action);
        jdbc.update(
                "INSERT INTO audit_trail (user_name,user_id,action,prev_hash,entry_hash) "
                        + "VALUES (:user_name,:user_id,:action,:prev_hash,:entry_hash)",
                new MapSqlParameterSource()
                        .addValue("user_name", actorType)
                        .addValue("user_id", actor)
                        .addValue("action", action)
                        .addValue("prev_hash", previous)
                        .addValue("entry_hash", hash));
    }

    private void insertMerchant(long merchantId, String actor, String actorType, String action) {
        String previous =
                AuditChainService.fetchLastHash(Common.DB_TABLE_AUDIT_TRAIL_MERCHANT, jdbc);
        String merchant = Long.toString(merchantId);
        String hash =
                AuditChainService.computeEntryHash(previous, actorType, actor, merchant, action);
        jdbc.update(
                "INSERT INTO merchants_audit_trail "
                        + "(user_name,user_id,merchant_id,action,prev_hash,entry_hash) "
                        + "VALUES (:user_name,:user_id,:merchant_id,:action,:prev_hash,:entry_hash)",
                new MapSqlParameterSource()
                        .addValue("user_name", actorType)
                        .addValue("user_id", actor)
                        .addValue("merchant_id", merchantId)
                        .addValue("action", action)
                        .addValue("prev_hash", previous)
                        .addValue("entry_hash", hash));
    }

    private String buildAction(
            PlatformTenantContext context,
            String action,
            String resourceType,
            String resourceId,
            Map<String, Object> summary) {
        String fields =
                summary == null || summary.isEmpty()
                        ? "[]"
                        : new TreeSet<>(summary.keySet()).toString();
        return "CITO_PLATFORM action="
                + action
                + " resourceType="
                + resourceType
                + " resourceId="
                + blank(resourceId)
                + " environment="
                + blank(context.environment())
                + " application="
                + blank(context.applicationId())
                + " correlation="
                + blank(context.correlationId())
                + " summaryFields="
                + fields;
    }

    private String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Audit value is required");
        return value.trim();
    }

    private String blank(String value) {
        return value == null ? "" : value.trim();
    }
}
