package com.subjex.platform.app.admin;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.VIEWER_PASSWORD;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * RateLimitCircuitObservationSecurityTest — GET /api/v1/rate-limits needs admin.read.
 * 限额与熔断观察读取需要 admin.read。
 */
@WebMvcTest(controllers = RateLimitCircuitObservationEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    RateLimitCircuitObservationSecurityTest.GuardConfiguration.class
})
class RateLimitCircuitObservationSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RateLimitCircuitObservationService observation;

    @Test
    void unauthenticatedGets401() throws Exception {
        mockMvc.perform(get(RateLimitCircuitObservationEndpoint.PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void readerCanReadSnapshot() throws Exception {
        when(observation.snapshot())
                .thenReturn(new RateLimitCircuitObservationService.ObservationDocument(
                        Instant.parse("2026-11-23T02:00:00Z"),
                        new RateLimitCircuitObservationService.RateLimitObservation(
                                "process", true, 60, 60, List.of(), false, false),
                        new RateLimitCircuitObservationService.CircuitObservation(
                                "process", true, "outbox", "closed", 0, 3, 30, null, null)));
        mockMvc.perform(get(RateLimitCircuitObservationEndpoint.PATH).with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.circuit.state").value("closed"))
                .andExpect(jsonPath("$.rateLimit.rejectionCountRecorded").value(false));
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
