package com.subjex.platform.app.admin;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.jdbc.JdbcAdminReader;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AdminReadEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    PlatformExceptionAdvice.class,
    AdminReadSecurityTest.GuardConfiguration.class
})
class AdminReadSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JdbcAdminReader adminReader;

    @MockitoBean
    private ApplicationAvailability availability;

    @Test
    void adminRequiresTheOperator() throws Exception {
        mockMvc.perform(get("/admin/tenants")).andExpect(status().isUnauthorized());
    }

    @Test
    void operatorCanReadTenantsTasksDeadLettersAndHealth() throws Exception {
        when(adminReader.tenants()).thenReturn(List.of());
        when(adminReader.tasks()).thenReturn(List.of());
        when(adminReader.deadLetters()).thenReturn(List.of());
        when(availability.getLivenessState()).thenReturn(LivenessState.CORRECT);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);

        mockMvc.perform(get("/admin/tenants").with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/tasks").with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/dead-letters").with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/health").with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liveness").value("CORRECT"))
                .andExpect(jsonPath("$.readiness").value("ACCEPTING_TRAFFIC"));
    }

    @Test
    void adminDoesNotAcceptWrites() throws Exception {
        mockMvc.perform(post("/admin/tenants").with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post("/admin/dead-letters").with(httpBasic("platform-operator", "change-me")))
                .andExpect(status().isMethodNotAllowed());
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
