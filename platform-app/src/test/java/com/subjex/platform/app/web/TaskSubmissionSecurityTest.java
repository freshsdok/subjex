package com.subjex.platform.app.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.contract.ratelimit.RateLimitPort;
import com.subjex.platform.contract.task.TaskKind;
import com.subjex.platform.contract.task.TaskMessagePort;
import com.subjex.platform.contract.task.TaskRecord;
import com.subjex.platform.contract.task.TaskState;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = TaskSubmissionEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    PlatformExceptionAdvice.class,
    TaskSubmissionSecurityTest.GuardConfiguration.class
})
class TaskSubmissionSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TaskMessagePort taskMessagePort;

    @MockitoBean
    private RateLimitPort rateLimitPort;

    @Test
    void missingOperatorIsUnauthorized() throws Exception {
        mockMvc.perform(post("/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingTenantIsForbidden() throws Exception {
        mockMvc.perform(post("/tasks")
                        .with(httpBasic("platform-operator", "change-me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isForbidden());
    }

    @Test
    void rateLimitRejectsBeforeTheTaskPort() throws Exception {
        when(rateLimitPort.permit(anyString(), anyString())).thenReturn(false);
        mockMvc.perform(post("/tasks")
                        .with(httpBasic("platform-operator", "change-me"))
                        .header("X-Tenant-Id", "tenant-north")
                        .header("X-Idempotency-Token", "token-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void acceptedSubmissionIsCreated() throws Exception {
        when(rateLimitPort.permit(anyString(), anyString())).thenReturn(true);
        when(taskMessagePort.submit(any())).thenReturn(new TaskRecord(
                "task-1",
                "tenant-north",
                TaskKind.DETERMINISTIC,
                TaskState.COMPLETED,
                "admit-subject",
                1,
                3,
                null,
                null,
                null,
                null,
                Instant.parse("2026-10-05T00:00:00Z")));
        mockMvc.perform(post("/tasks")
                        .with(httpBasic("platform-operator", "change-me"))
                        .header("X-Tenant-Id", "tenant-north")
                        .header("X-Idempotency-Token", "token-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isCreated());
    }

    private static String body() {
        return """
                {"actorIdentityId":"identity-1","taskKind":"DETERMINISTIC","stepName":"admit-subject","stepSucceeded":true}
                """;
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
