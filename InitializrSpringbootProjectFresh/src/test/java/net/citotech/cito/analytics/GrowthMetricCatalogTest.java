package net.citotech.cito.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GrowthMetricCatalogTest {

    @Test
    void mapsServicesIntoTheFiveCommercialProductFamilies() {
        assertThat(GrowthMetricCatalog.familyForService("CPAY")).isEqualTo("PAYMENTS");
        assertThat(GrowthMetricCatalog.familyForService("COMMUNICATIONS"))
                .isEqualTo("COMMUNICATIONS");
        assertThat(GrowthMetricCatalog.familyForService("VENDING"))
                .isEqualTo("VENDING_UTILITIES");
        assertThat(GrowthMetricCatalog.familyForService("BILLING")).isEqualTo("BILLING_BAAS");
        assertThat(GrowthMetricCatalog.familyForService("IDENTITY_VALIDATION"))
                .isEqualTo("KYC_IDENTITY");
    }

    @Test
    void leavesNonCommercialSupportServicesOutsideAttachRate() {
        assertThat(GrowthMetricCatalog.familyForService("MERCHANT_ANALYTICS")).isNull();
        assertThat(GrowthMetricCatalog.familyForService("DEVELOPER_CONTROL_PLANE")).isNull();
    }

    @Test
    void publishesExplicitRetentionDefinitions() {
        assertThat(GrowthMetricCatalog.definitions())
                .extracting(GrowthMetricCatalog.MetricDefinition::code)
                .contains("DAY_7_RETENTION", "DAY_30_RETENTION", "DAY_90_RETENTION");
    }
}
