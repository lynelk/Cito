package net.citotech.cito.portal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import net.citotech.cito.Model.MerchantUser;
import net.citotech.cito.SettingsRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = PortalV2AuthorizationTest.Config.class)
class PortalV2AuthorizationTest {
    @Autowired PortalV2Controller portal;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @Test
    @WithMockUser(roles = "MERCHANT")
    void merchantCannotReadGlobalPortalLists() {
        assertThatThrownBy(() -> portal.merchants(100)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> portal.settings()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> portal.transactions(100))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> portal.sms(100)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void administratorCanReadGlobalPortalLists() {
        assertThat(portal.merchants(100).get("items")).isEqualTo(List.of());
        assertThat(portal.settings().get("items")).isEqualTo(List.of());
        assertThat(portal.transactions(100).get("items")).isEqualTo(List.of());
        assertThat(portal.sms(100).get("items")).isEqualTo(List.of());
    }

    @Test
    @WithMockUser(roles = "MERCHANT")
    void incompleteMerchantSessionCannotBecomeAdministratorScope() {
        var session = new MockHttpSession();
        session.setAttribute("merchantUser", new MerchantUser());
        assertThatThrownBy(() -> portal.dashboardSummary(session))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> portal.dashboardSummary(null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "MERCHANT")
    void merchantDashboardScopesCallbacksAndExcludesPlatformAlerts() {
        reset(jdbc);
        when(jdbc.queryForObject(
                        contains("active_environment"),
                        any(MapSqlParameterSource.class),
                        eq(String.class)))
                .thenReturn("PRODUCTION");
        when(jdbc.queryForObject(
                        contains("FROM callback_tasks"),
                        any(MapSqlParameterSource.class),
                        eq(Integer.class)))
                .thenAnswer(
                        call -> {
                            assertThat((String) call.getArgument(0))
                                    .contains("merchant_id=:merchant_id");
                            assertThat(
                                            ((MapSqlParameterSource) call.getArgument(1))
                                                    .getValue("merchant_id"))
                                    .isEqualTo(41L);
                            return 7;
                        });
        var merchant = new MerchantUser();
        merchant.setId(12L);
        merchant.setMerchant_id(41L);
        merchant.setMerchant_number("merchant-41");
        var session = new MockHttpSession();
        session.setAttribute("merchantUser", merchant);
        try (var settings = mockStatic(SettingsRegistry.class)) {
            var response = portal.dashboardSummary(session);
            assertThat(response.get("scope")).isEqualTo("MERCHANT");
            assertThat(response.get("pendingCallbacks")).isEqualTo(7);
            assertThat(response.get("recentNotifications")).isEqualTo(List.of());
            verify(jdbc, never())
                    .queryForList(
                            contains("FROM operations_alerts"), any(MapSqlParameterSource.class));
        }
    }

    @Test
    @WithMockUser(roles = "MERCHANT")
    void sandboxDashboardDoesNotPresentProductionMoneyOrCallbacks() {
        reset(jdbc);
        when(jdbc.queryForObject(
                        contains("active_environment"),
                        any(MapSqlParameterSource.class),
                        eq(String.class)))
                .thenReturn("SANDBOX");
        var merchant = new MerchantUser();
        merchant.setId(12L);
        merchant.setMerchant_id(41L);
        merchant.setMerchant_number("merchant-41");
        var session = new MockHttpSession();
        session.setAttribute("merchantUser", merchant);
        try (var settings = mockStatic(SettingsRegistry.class)) {
            var response = portal.dashboardSummary(session);
            assertThat(response.get("environment")).isEqualTo("SANDBOX");
            assertThat(response.get("pendingCallbacks")).isEqualTo(0);
            assertThat(response.get("channelBalances")).isEqualTo(List.of());
            verify(jdbc)
                    .queryForObject(
                            eq(
                                    "SELECT COUNT(*) FROM merchant_sandbox_transactions WHERE merchant_id=:merchant_id"),
                            any(MapSqlParameterSource.class),
                            eq(Integer.class));
            verify(jdbc, never())
                    .queryForObject(
                            contains("merchant_production_transactions"),
                            any(MapSqlParameterSource.class),
                            any(Class.class));
            verify(jdbc, never())
                    .queryForObject(
                            contains("FROM callback_tasks"),
                            any(MapSqlParameterSource.class),
                            eq(Integer.class));
            verify(jdbc)
                    .queryForList(
                            contains(
                                    "FROM merchant_channel_credentials WHERE environment=:environment"),
                            argThat(
                                    (MapSqlParameterSource p) ->
                                            "SANDBOX".equals(p.getValue("environment"))));
        }
    }

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        NamedParameterJdbcTemplate jdbc() {
            return mock(NamedParameterJdbcTemplate.class);
        }

        @Bean
        PortalV2Controller portal(NamedParameterJdbcTemplate jdbc) {
            return new PortalV2Controller(jdbc);
        }
    }
}
