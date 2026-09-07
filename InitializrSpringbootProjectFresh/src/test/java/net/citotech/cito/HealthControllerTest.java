package net.citotech.cito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class HealthControllerTest {

    @Test
    void returnsOkAndReleaseIdentityWhenEveryDatabaseCheckSucceeds() {
        NamedParameterJdbcTemplate jdbcTemplate = healthyJdbcTemplate();
        HealthController controller = controller(jdbcTemplate);

        ResponseEntity<String> response = controller.health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JSONObject body = new JSONObject(response.getBody());
        assertThat(body.getString("status")).isEqualTo("UP");
        assertThat(body.getString("db")).isEqualTo("UP");
        assertThat(body.getString("release_sha")).isEqualTo("release-under-test");
    }

    @Test
    void returnsServiceUnavailableWhenDatabaseConnectivityFails() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForObject(
                        eq("SELECT 1"), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        HealthController controller = controller(jdbcTemplate);

        ResponseEntity<String> response = controller.health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        JSONObject body = new JSONObject(response.getBody());
        assertThat(body.getString("status")).isEqualTo("DOWN");
        assertThat(body.getString("db")).isEqualTo("DOWN");
    }

    @Test
    void returnsServiceUnavailableWhenAnOperationalDatabaseQueryFails() {
        NamedParameterJdbcTemplate jdbcTemplate = healthyJdbcTemplate();
        when(jdbcTemplate.queryForObject(
                        contains("status='PENDING'"),
                        any(MapSqlParameterSource.class),
                        eq(Long.class)))
                .thenThrow(new DataAccessResourceFailureException("query failed"));
        HealthController controller = controller(jdbcTemplate);

        ResponseEntity<String> response = controller.health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(new JSONObject(response.getBody()).getString("status")).isEqualTo("DOWN");
    }

    private NamedParameterJdbcTemplate healthyJdbcTemplate() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForObject(
                        eq("SELECT 1"), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(
                        contains("status='PENDING'"),
                        any(MapSqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(0L);
        when(jdbcTemplate.queryForObject(
                        contains("callback_status='FAILED'"),
                        any(MapSqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(0L);
        return jdbcTemplate;
    }

    private HealthController controller(NamedParameterJdbcTemplate jdbcTemplate) {
        HealthController controller = new HealthController();
        controller.jdbcTemplate = jdbcTemplate;
        ReflectionTestUtils.setField(controller, "gatewaystate", "live");
        ReflectionTestUtils.setField(controller, "releaseSha", "release-under-test");
        return controller;
    }
}
