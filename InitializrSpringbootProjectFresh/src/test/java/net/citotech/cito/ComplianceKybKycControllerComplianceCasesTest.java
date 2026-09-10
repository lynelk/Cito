package net.citotech.cito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class ComplianceKybKycControllerComplianceCasesTest {

    @Test
    void projectsTheCanonicalLegacyCaseSchemaIntoTheAdminResponseContract() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response =
                new ComplianceKybKycController(jdbc).listComplianceCases(null, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture());
        assertThat(sql.getValue())
                .contains(
                        "case_status as status",
                        "entity_type as subject_type",
                        "cast(entity_id as char) as subject_reference",
                        "source_reference as transaction_reference",
                        "created_at as opened_at",
                        "order by created_at desc")
                .doesNotContain("order by opened_at desc");
        assertThat(response.getBody()).containsEntry("status", "OK");
        assertThat(response.getBody()).containsEntry("cases", List.of());
    }

    @Test
    void appliesStatusAndSubjectFiltersToColumnsThatExistInTheLegacyTable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq("OPEN"), eq("42"))).thenReturn(List.of());

        new ComplianceKybKycController(jdbc).listComplianceCases("OPEN", "42");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), eq("OPEN"), eq("42"));
        assertThat(sql.getValue())
                .contains(
                        "case_status = ?",
                        "cast(entity_id as char) = ?",
                        "order by created_at desc")
                .doesNotContain("and status = ?", "and subject_reference = ?");
    }
}
