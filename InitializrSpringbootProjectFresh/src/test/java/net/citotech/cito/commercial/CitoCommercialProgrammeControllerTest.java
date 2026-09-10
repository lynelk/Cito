package net.citotech.cito.commercial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CitoCommercialProgrammeControllerTest {

    @Test
    void listsPackagesFromCommercialService() {
        CitoCommercialProgrammeService service = mock(CitoCommercialProgrammeService.class);
        when(service.packages()).thenReturn(List.of(Map.of("packageCode", "GROWTH")));

        List<Map<String, Object>> response = new CitoCommercialProgrammeController(service).packages();

        assertThat(response).hasSize(1);
        assertThat(response.get(0)).containsEntry("packageCode", "GROWTH");
    }

    @Test
    void delegatesFounding20EnrollmentWithBoundedSlot() {
        CitoCommercialProgrammeService service = mock(CitoCommercialProgrammeService.class);
        when(service.enrollFounding20(anyLong(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("cohortSlot", 3, "programmeStatus", "CANDIDATE"));
        CitoCommercialProgrammeController controller = new CitoCommercialProgrammeController(service);

        Map<String, Object> response =
                controller.enrollFounding20(
                        42L,
                        Map.of(
                                "cohortSlot", 3,
                                "commercialOwner", "sales@example.test",
                                "actor", "admin@example.test"));

        assertThat(response).containsEntry("cohortSlot", 3);
        verify(service).enrollFounding20(
                anyLong(), anyInt(), any(), any(), any(), any(), anyString());
    }

    @Test
    void delegatesEmbeddedPartnerEnrollmentToExistingProgrammeService() {
        CitoCommercialProgrammeService service = mock(CitoCommercialProgrammeService.class);
        when(service.enrollEmbeddedPartner(anyLong(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("programmeStatus", "CANDIDATE"));
        CitoCommercialProgrammeController controller = new CitoCommercialProgrammeController(service);

        Map<String, Object> response =
                controller.enrollEmbeddedPartner(
                        77L,
                        Map.of(
                                "programmeTier", "STRATEGIC",
                                "targetDownstreamMerchants", 25,
                                "actor", "admin@example.test"));

        assertThat(response).containsEntry("programmeStatus", "CANDIDATE");
        verify(service).enrollEmbeddedPartner(anyLong(), any(), any(), any(), any(), any(), anyString());
    }
}
