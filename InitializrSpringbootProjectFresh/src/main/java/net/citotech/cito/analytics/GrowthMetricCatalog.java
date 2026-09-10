package net.citotech.cito.analytics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonical business definitions for Cito growth reporting. These definitions deliberately point
 * at existing durable operational sources rather than creating a second activation, usage, or
 * revenue truth.
 */
public final class GrowthMetricCatalog {
    private static final Map<String, String> SERVICE_FAMILIES = serviceFamilies();

    private static final List<MetricDefinition> DEFINITIONS =
            List.of(
                    new MetricDefinition(
                            "SIGNED_MERCHANTS",
                            "Signed merchants",
                            "Merchants created in the selected reporting window.",
                            "merchants.created_on"),
                    new MetricDefinition(
                            "LIVE_MERCHANTS",
                            "Live merchants",
                            "Merchants whose canonical activation lifecycle status is LIVE.",
                            "merchant_activation_lifecycles.status"),
                    new MetricDefinition(
                            "DAY_7_RETENTION",
                            "Day 7 retention",
                            "Merchants activated at least seven days ago with durable production usage on or after activation plus seven days, divided by all merchants eligible for that observation.",
                            "merchant_activation_lifecycles + merchant_production_usage"),
                    new MetricDefinition(
                            "DAY_30_RETENTION",
                            "Day 30 retention",
                            "Merchants activated at least thirty days ago with durable production usage on or after activation plus thirty days, divided by all merchants eligible for that observation.",
                            "merchant_activation_lifecycles + merchant_production_usage"),
                    new MetricDefinition(
                            "DAY_90_RETENTION",
                            "Day 90 retention",
                            "Merchants activated at least ninety days ago with durable production usage on or after activation plus ninety days, divided by all merchants eligible for that observation.",
                            "merchant_activation_lifecycles + merchant_production_usage"),
                    new MetricDefinition(
                            "WEEKLY_ACTIVE_MERCHANTS",
                            "Weekly active merchants",
                            "Distinct merchants with durable production usage during the last seven days.",
                            "merchant_production_usage"),
                    new MetricDefinition(
                            "MONTHLY_ACTIVE_MERCHANTS",
                            "Monthly active merchants",
                            "Distinct merchants with durable production usage during the last thirty days.",
                            "merchant_production_usage"),
                    new MetricDefinition(
                            "DORMANT_MERCHANTS",
                            "Dormant merchants",
                            "Merchants with production usage 31-90 days ago and no production usage in the last thirty days.",
                            "merchant_production_usage"),
                    new MetricDefinition(
                            "SERVICE_ATTACH_RATE",
                            "Service attach rate",
                            "Share of monthly active merchants with active production entitlements in at least two canonical Cito product families.",
                            "cito_service_entitlements"),
                    new MetricDefinition(
                            "PAYMENT_SUCCESS_RATE",
                            "Payment success rate",
                            "Successful payment transactions divided by all payment transactions in the selected window.",
                            "merchant_transactions_log"),
                    new MetricDefinition(
                            "BILLED_REVENUE",
                            "Billed revenue",
                            "Effective rated customer charges in the selected window, reported by currency.",
                            "billing_rated_charges"),
                    new MetricDefinition(
                            "PROVIDER_COST",
                            "Provider cost",
                            "Effective rated provider costs in the selected window, reported by currency.",
                            "billing_rated_charges"),
                    new MetricDefinition(
                            "GROSS_MARGIN",
                            "Gross margin",
                            "Billed revenue less provider cost for the same currency and reporting window.",
                            "billing_rated_charges"),
                    new MetricDefinition(
                            "ACTIVATION_TIME",
                            "Activation time",
                            "Elapsed time from lifecycle creation to recorded production activation; median is calculated only from merchants with both timestamps.",
                            "merchant_activation_lifecycles"));

    private GrowthMetricCatalog() {}

    public static List<MetricDefinition> definitions() {
        return DEFINITIONS;
    }

    public static Map<String, String> serviceFamilies() {
        Map<String, String> families = new LinkedHashMap<>();
        for (String service :
                Set.of(
                        "CPAY",
                        "MARKETPLACE_PAYMENTS",
                        "RECURRING_PAYMENTS",
                        "VIRTUAL_ACCOUNTS",
                        "INTELLIGENT_ROUTING",
                        "REFUND_OPERATIONS")) {
            families.put(service, "PAYMENTS");
        }
        families.put("COMMUNICATIONS", "COMMUNICATIONS");
        families.put("VENDING", "VENDING_UTILITIES");
        families.put("BILLING", "BILLING_BAAS");
        families.put("EMBEDDED_CITO", "BILLING_BAAS");
        families.put("IDENTITY_VALIDATION", "KYC_IDENTITY");
        families.put("INTEGRATIONS_MARKETPLACE", "INTEGRATIONS_AUTOMATION");
        return Map.copyOf(families);
    }

    public static String familyForService(String serviceCode) {
        if (serviceCode == null) {
            return null;
        }
        return SERVICE_FAMILIES.get(serviceCode.trim().toUpperCase());
    }

    public record MetricDefinition(String code, String name, String definition, String source) {}
}
