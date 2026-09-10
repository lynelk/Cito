package net.citotech.cito.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class GrowthMilestoneProjectionServiceTest {

    @Test
    void projectsOnlyDurableCanonicalMilestonesIdempotently() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        Map<String, Integer> result = new GrowthMilestoneProjectionService(jdbc).reconcile();

        assertThat(result)
                .containsKeys(
                        "MERCHANT_ACCOUNT_CREATED",
                        "MERCHANT_USER_ACTIVATED",
                        "SANDBOX_CREDENTIAL_READY",
                        "FIRST_SANDBOX_SUCCESS",
                        "PROVIDER_CONFIGURED",
                        "GO_LIVE_APPROVED",
                        "PRODUCTION_ACTIVATED",
                        "FIRST_PRODUCTION_SUCCESS");
        verify(jdbc, times(8)).update(anyString(), any(MapSqlParameterSource.class));
    }
}
