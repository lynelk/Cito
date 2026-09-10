package net.citotech.cito.developer.reference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ApiReferenceAccessTest.Config.class)
class ApiReferenceAccessTest {
    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        ApiAccessBillingService billing() {
            return mock(ApiAccessBillingService.class);
        }

        @Bean
        ApiReferenceService reference() {
            return mock(ApiReferenceService.class);
        }

        @Bean
        AdminApiReferenceController controller(
                ApiReferenceService reference, ApiAccessBillingService billing) {
            return new AdminApiReferenceController(reference, billing);
        }
    }

    @Autowired AdminApiReferenceController controller;

    @Test
    @WithMockUser(roles = "MERCHANT")
    void merchantCannotReadAdminRates() {
        assertThatThrownBy(() -> controller.rates()).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "MERCHANT")
    void merchantCannotPublishRates() {
        assertThatThrownBy(
                        () ->
                                controller.publish(
                                        new AdminApiReferenceController.RateRequest(
                                                "GET", "/x", "0", "UGX", 1)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void administratorCanReadRates() {
        assertThat(controller.rates()).isNotNull();
    }
}
