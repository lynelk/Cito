package net.citotech.cito.gateway;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import net.citotech.cito.Api;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

class AirtelCallbackCanonicalHintTest {
    @Test void forgedTerminalClaimAndUnknownReferenceNeverReopenHistoricalFinalization() {
        var api = new Api();
        var recovery = mock(MobileMoneyRecoveryService.class);
        ReflectionTestUtils.setField(api, "mobileMoneyRecovery", recovery);
        var response = new MockHttpServletResponse();
        String id = "ed92dc1b-73ed-4dca-b012-b6d3b78aabfd";
        when(recovery.signal("airtel_open_api", id, id)).thenReturn(false);
        String result = api.doAirtelMoneyPayInCallback("{\"transaction\":{\"id\":\"" + id + "\",\"status\":\"SUCCESSFUL\"}}", new MockHttpServletRequest(), response);
        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(result).contains("verification scheduled");
        verify(recovery).signal("airtel_open_api", id, id);
        verifyNoMoreInteractions(recovery);
    }
}
