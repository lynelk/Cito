package net.citotech.cito.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;

class GrowthIntelligenceControllerTest {

    @Test
    void delegatesScorecardAndMerchantDrillDownWithoutChangingDefinitions() {
        GrowthIntelligenceService intelligence = mock(GrowthIntelligenceService.class);
        GrowthMilestoneProjectionService projection = mock(GrowthMilestoneProjectionService.class);
        when(intelligence.scorecard(30)).thenReturn(Map.of("windowDays", 30));
        when(intelligence.merchant(42L, 30)).thenReturn(Map.of("merchantId", 42L));

        GrowthIntelligenceController controller =
                new GrowthIntelligenceController(intelligence, projection);

        assertThat(controller.scorecard(30)).containsEntry("windowDays", 30);
        assertThat(controller.merchant(42L, 30)).containsEntry("merchantId", 42L);
        verify(intelligence).scorecard(30);
        verify(intelligence).merchant(42L, 30);
    }

    @Test
    void exposesExplicitMilestoneReconciliation() {
        GrowthIntelligenceService intelligence = mock(GrowthIntelligenceService.class);
        GrowthMilestoneProjectionService projection = mock(GrowthMilestoneProjectionService.class);
        when(projection.reconcile()).thenReturn(Map.of("FIRST_PRODUCTION_SUCCESS", 2));

        Map<String, Object> response =
                new GrowthIntelligenceController(intelligence, projection).reconcileMilestones();

        assertThat(response).containsKey("projected");
        verify(projection).reconcile();
    }
}
