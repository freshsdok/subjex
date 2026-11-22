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
 * LoadDashboardSecurityTest — GET /api/v1/load needs admin.read.
 * 负载快照读取需要 admin.read。
 */
@WebMvcTest(controllers = LoadDashboardEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    LoadDashboardSecurityTest.GuardConfiguration.class
})
class LoadDashboardSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LoadDashboardService load;

    @Test
    void unauthenticatedGets401() throws Exception {
        mockMvc.perform(get(LoadDashboardEndpoint.PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void readerCanReadSnapshot() throws Exception {
        when(load.snapshot())
                .thenReturn(new LoadDashboardService.LoadSnapshot(
                        Instant.parse("2026-11-23T00:30:00Z"),
                        "CORRECT",
                        "ACCEPTING_TRAFFIC",
                        new LoadDashboardService.ProcessSnapshot(null, null, null),
                        new LoadDashboardService.JvmSnapshot(null, null, null, null, null, null, null),
                        new LoadDashboardService.HttpSnapshot(false, null, null, null, List.of()),
                        new LoadDashboardService.PoolSnapshot(false, null, null, null, null),
                        new LoadDashboardService.TomcatSnapshot(false, null, null, null),
                        new LoadDashboardService.LogSnapshot(false, List.of()),
                        false,
                        false));
        mockMvc.perform(get(LoadDashboardEndpoint.PATH).with(httpBasic(VIEWER, VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liveness").value("CORRECT"));
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
