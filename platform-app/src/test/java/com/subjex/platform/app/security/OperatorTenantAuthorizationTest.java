package com.subjex.platform.app.security;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.app.tenant.TenantQuotaService;
import com.subjex.platform.app.web.TaskSubmissionEndpoint;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Operator–tenant grants on tenant-scoped paths — 租户作用域路径上的操作员—租户授权。
 */
@WebMvcTest(controllers = TaskSubmissionEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    OperatorTenantAuthorizationTest.GuardConfiguration.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OperatorTenantAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OperatorTenantAccess tenantAccess;

    @MockitoBean
    private TenantQuotaService tenantQuotaService;

    @MockitoBean
    private TaskMessagePort taskMessagePort;

    @MockitoBean
    private RateLimitPort rateLimitPort;

    @Test
    void wildcardGrantAllowsAnyTenant() throws Exception {
        when(rateLimitPort.permit(anyString(), anyString())).thenReturn(true);
        when(taskMessagePort.submit(any())).thenReturn(sampleTask("tenant-north"));
        mockMvc.perform(post("/tasks")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .header("X-Tenant-Id", "tenant-north")
                        .header("X-Idempotency-Token", "token-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isCreated());
    }

    @Test
    void missingGrantIsForbiddenEvenWhenHeaderPresent() throws Exception {
        // Drop the seeded '*' grant so only explicit tenants would pass.
        jdbc.update("DELETE FROM operator_tenant_grant WHERE subject_id = ?", LocalOperatorSeeder.SUBJECT_ID);
        when(rateLimitPort.permit(anyString(), anyString())).thenReturn(true);

        mockMvc.perform(post("/tasks")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .header("X-Tenant-Id", "tenant-north")
                        .header("X-Idempotency-Token", "token-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isForbidden());

        tenantAccess.ensureGrant(LocalOperatorSeeder.SUBJECT_ID, "tenant-north");
        when(taskMessagePort.submit(any())).thenReturn(sampleTask("tenant-north"));
        mockMvc.perform(post("/tasks")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .header("X-Tenant-Id", "tenant-north")
                        .header("X-Idempotency-Token", "token-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/tasks")
                        .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                        .header("X-Tenant-Id", "tenant-south")
                        .header("X-Idempotency-Token", "token-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isForbidden());
    }

    private static String body() {
        return """
                {"actorIdentityId":"identity-1","taskKind":"DETERMINISTIC","stepName":"admit-subject","stepSucceeded":true}
                """;
    }

    private static TaskRecord sampleTask(String tenantId) {
        return new TaskRecord(
                "task-1",
                tenantId,
                TaskKind.DETERMINISTIC,
                TaskState.COMPLETED,
                "admit-subject",
                1,
                3,
                null,
                null,
                null,
                null,
                Instant.parse("2026-10-05T00:00:00Z"));
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }
}
