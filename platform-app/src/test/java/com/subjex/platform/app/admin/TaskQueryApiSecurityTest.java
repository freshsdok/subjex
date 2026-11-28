package com.subjex.platform.app.admin;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.jdbc.JdbcAdminReader;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.task.HumanConfirmation;
import com.subjex.platform.contract.task.TaskKind;
import com.subjex.platform.contract.task.TaskRecord;
import com.subjex.platform.contract.task.TaskState;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * TaskQueryApiSecurityTest — GET /api/v1/tasks is admin.read (operator and reader); no writes on this path.
 * 门禁：只读任务列表要 admin.read（操作员与只读员均可）；本路径不接受写。
 */
@WebMvcTest(controllers = TaskQueryEndpoint.class)
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    TaskQueryApiSecurityTest.GuardConfiguration.class
})
class TaskQueryApiSecurityTest {

    private static final Instant T0 = Instant.parse("2026-11-28T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JdbcAdminReader adminReader;

    @Test
    void anonymousIsRefused() throws Exception {
        mockMvc.perform(get(TaskQueryEndpoint.PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void operatorAndReaderCanListTasks() throws Exception {
        when(adminReader.tasks())
                .thenReturn(List.of(new TaskRecord(
                        "task-1",
                        "tenant-a",
                        TaskKind.DETERMINISTIC,
                        TaskState.COMPLETED,
                        "step-a",
                        1,
                        3,
                        null,
                        "digest",
                        HumanConfirmation.UNCONFIRMED,
                        null,
                        T0)));
        mockMvc.perform(get(TaskQueryEndpoint.PATH)
                        .with(httpBasic(
                                OperatorDirectoryTestConfiguration.OPERATOR,
                                OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].taskId").value("task-1"))
                .andExpect(jsonPath("$.tasks[0].tenantId").value("tenant-a"))
                .andExpect(jsonPath("$.tasks[0].taskKind").value("DETERMINISTIC"))
                .andExpect(jsonPath("$.tasks[0].taskState").value("COMPLETED"))
                .andExpect(jsonPath("$.tasks[0].attemptCount").value(1))
                .andExpect(jsonPath("$.tasks[0].maxAttempt").value(3))
                .andExpect(jsonPath("$.tasks[0].humanConfirmation").value("UNCONFIRMED"));
        // platform-reader already holds admin.read (same as GET /admin/tasks).
        // platform-reader 已有 admin.read（与 GET /admin/tasks 相同）。
        mockMvc.perform(get(TaskQueryEndpoint.PATH)
                        .with(httpBasic(
                                OperatorDirectoryTestConfiguration.VIEWER,
                                OperatorDirectoryTestConfiguration.VIEWER_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(1));
    }

    @Test
    void listPathDoesNotAcceptWrites() throws Exception {
        mockMvc.perform(post(TaskQueryEndpoint.PATH)
                        .with(httpBasic(
                                OperatorDirectoryTestConfiguration.OPERATOR,
                                OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD)))
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
