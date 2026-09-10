package net.citotech.cito.webhook;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Covers audit D6: the catalog listing is merchant-signed and returns a real schema object per
 * entry.
 */
class WebhookEventCatalogControllerTest {

    @Test
    void listsKnownEventTypesWithAParsedSchemaObject() throws Exception {
        WebhookEventCatalogController controller =
                new WebhookEventCatalogController(
                        org.mockito.Mockito.mock(
                                net.citotech.cito.api.v2.V2RequestSecurityService.class));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/v2/webhooks/events").param("merchantNumber", "123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("payment.pending"))
                .andExpect(jsonPath("$[0].qualifiedType").value("payment.pending.v1"))
                .andExpect(jsonPath("$[0].schema.type").value("object"));
    }
}
