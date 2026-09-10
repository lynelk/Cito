package net.citotech.cito.billing;

import java.sql.Timestamp;
import net.citotech.cito.billing.invoicing.BillingInvoiceRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Synthetic source evidence for isolated integration-test databases, never a runtime waiver. */
public final class SyntheticBillingEvidence {
    private SyntheticBillingEvidence() {}

    public static void recordCompleteSource(
            NamedParameterJdbcTemplate jdbc, BillingInvoiceRepository invoices, long invoiceId) {
        var invoice = invoices.find(invoiceId).orElseThrow();
        var through = Timestamp.valueOf(invoice.periodEnd().atTime(23, 59, 59));
        // Explicit test-only zero-rate rule. This is not a Uganda tax assumption and
        // is never loaded by production. The runtime must still find an approved rule.
        jdbc.update(
                "INSERT INTO billing_tax_rule_versions"
                        + " (billing_tenant_id,tax_code,currency,rate,status,effective_from,effective_to,created_by,approved_by,approved_at)"
                        + " VALUES (:tenant,'STANDARD',:currency,0,'APPROVED',:start,:end,'test-tax-maker','test-tax-checker',:start)",
                new MapSqlParameterSource("tenant", invoice.billingTenantId())
                        .addValue("currency", invoice.currency())
                        .addValue("start", Timestamp.valueOf(invoice.periodStart().atStartOfDay()))
                        .addValue(
                                "end",
                                Timestamp.valueOf(invoice.periodEnd().plusDays(1).atStartOfDay())));

        jdbc.update(
                "INSERT INTO billing_source_watermarks"
                        + " (billing_tenant_id, source_code, service_code, expected_through_at, observed_through_at, status)"
                        + " VALUES (:tenant, 'ISOLATED_TEST_FIXTURE', 'PAYMENT', :through, :through, 'COMPLETE')",
                new MapSqlParameterSource("tenant", invoice.billingTenantId())
                        .addValue("through", through));
    }
}
