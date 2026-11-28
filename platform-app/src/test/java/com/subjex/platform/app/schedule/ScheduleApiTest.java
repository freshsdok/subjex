package com.subjex.platform.app.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.capability.CapabilityCatalog;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.JdbcOperatorTenantAccess;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.PolicyEngineFactory;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.app.workflow.JdbcWorkflowStore;
import com.subjex.platform.app.workflow.WorkflowCatalog;
import com.subjex.platform.app.workflow.WorkflowService;
import com.subjex.platform.contract.schedule.ScheduleClaim;
import com.subjex.platform.contract.task.DeadLetter;
import com.subjex.platform.contract.task.OutboxEvent;
import com.subjex.platform.contract.task.TaskCommand;
import com.subjex.platform.contract.task.TaskKind;
import com.subjex.platform.contract.task.TaskMessagePort;
import com.subjex.platform.contract.task.TaskRecord;
import com.subjex.platform.contract.task.TaskState;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ScheduleApiTest — create/list/enable/disable, due claim, fail-closed cron/workflow, tenant isolation, lease.
 * 创建/列表/启用/停用、到期领取、非法 cron/缺失工作流失败关闭、租户隔离与租约。
 */
class ScheduleApiTest {

    private static final String TENANT_A = "tenant-schedule-a";
    private static final String TENANT_B = "tenant-schedule-b";
    private static final Instant T0 = Instant.parse("2026-11-28T12:00:00Z");

    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private AdjustableClock clock;
    private JdbcSchedulePort port;
    private RecordingTaskPort tasks;
    private WorkflowService workflows;
    private ScheduleWorker worker;
    private MockMvc mvc;
    private OperatorPrincipal reader;
    private OperatorPrincipal writer;

    @BeforeEach
    void setUp() {
        DataSource ds = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        jdbc = new JdbcTemplate(ds);
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                TENANT_A,
                "Tenant A");
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                TENANT_B,
                "Tenant B");
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        clock = new AdjustableClock(T0);
        JdbcOperatorTenantAccess tenantAccess = new JdbcOperatorTenantAccess(jdbc, tx);
        port = new JdbcSchedulePort(jdbc, tx, clock);
        tasks = new RecordingTaskPort(clock);
        workflows = new WorkflowService(
                new JdbcWorkflowStore(jdbc, tx),
                tenantAccess,
                new CapabilityCatalog(),
                new OperatorActionAudit(new JdbcAuditPort(jdbc), clock),
                clock);
        OperatorScheduleService service = new OperatorScheduleService(
                port,
                new WorkflowCatalog(),
                tenantAccess,
                PolicyEngineFactory.create("sql", new DenyWhenTenantMissing(), tenantAccess),
                clock);
        worker = new ScheduleWorker(port, tasks, workflows, tx, clock, 10, "worker-a");
        mvc = MockMvcBuilders.standaloneSetup(new OperatorScheduleEndpoint(service))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
        reader = operator("reader", "schedule.read");
        writer = operator("writer", "schedule.read", "schedule.write");
        grant(reader, TENANT_A);
        grant(writer, TENANT_A);
        grant(writer, TENANT_B);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void operatorCreatesListsEnablesAndDisables() throws Exception {
        String created = mvc.perform(post(OperatorScheduleEndpoint.PATH)
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskBody("Nightly check", "0 0 9 * * *", true, "nightly-check")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Nightly check"))
                .andExpect(jsonPath("$.cronExpression").value("0 0 9 * * *"))
                .andExpect(jsonPath("$.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.actionType").value("ENQUEUE_TASK"))
                .andExpect(jsonPath("$.taskStepName").value("nightly-check"))
                .andExpect(jsonPath("$.nextRunAt").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String scheduleId = readJson(created, "scheduleId");

        mvc.perform(get(OperatorScheduleEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].scheduleId").value(scheduleId));

        mvc.perform(post(OperatorScheduleEndpoint.PATH + "/" + scheduleId + "/disable")
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mvc.perform(post(OperatorScheduleEndpoint.PATH + "/" + scheduleId + "/enable")
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.nextRunAt").exists());

        mvc.perform(post(OperatorScheduleEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskBody("Denied", "0 0 9 * * *", true, "denied")))
                .andExpect(status().isForbidden());
    }

    @Test
    void dueScheduleEnqueuesDeterministicTaskAndSecondSweepDoesNotDoubleFire() throws Exception {
        String created = mvc.perform(post(OperatorScheduleEndpoint.PATH)
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskBody("Due task", "0 0 9 * * *", true, "due-step")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String scheduleId = readJson(created, "scheduleId");
        Instant next = port.find(TENANT_A, scheduleId).orElseThrow().nextRunAt();

        assertEquals(0, worker.processDue(10));
        assertTrue(tasks.submitted.isEmpty());

        clock.set(next);
        assertEquals(1, worker.processDue(10));
        assertEquals(1, tasks.submitted.size());
        TaskCommand command = tasks.submitted.get(0);
        assertEquals(TENANT_A, command.tenantId());
        assertEquals(TaskKind.DETERMINISTIC, command.taskKind());
        assertEquals("due-step", command.stepName());
        assertTrue(command.stepSucceeded());
        assertEquals(ScheduleCron.taskIdempotencyToken(scheduleId, next), command.idempotencyToken());
        assertEquals(writer.identityId(), command.actorIdentityId());
        assertNull(command.modelId());

        assertEquals(0, worker.processDue(10));
        assertEquals(1, tasks.submitted.size());
        assertNotNull(port.find(TENANT_A, scheduleId).orElseThrow().lastRunAt());
        assertTrue(port.find(TENANT_A, scheduleId).orElseThrow().nextRunAt().isAfter(next));
    }

    @Test
    void dueScheduleStartsClasspathWorkflowOncePerTick() throws Exception {
        String created = mvc.perform(post(OperatorScheduleEndpoint.PATH)
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workflowBody("Repair sweep", "0 0 9 * * *", true, "repair-ticket", "ticket-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionType").value("START_WORKFLOW"))
                .andExpect(jsonPath("$.workflowDefinitionKey").value("repair-ticket"))
                .andExpect(jsonPath("$.workflowEntityKey").value("repair-ticket"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String wfId = readJson(created, "scheduleId");
        Instant next = port.find(TENANT_A, wfId).orElseThrow().nextRunAt();
        clock.set(next);
        assertEquals(1, worker.processDue(10));
        String recordId = ScheduleCron.workflowRecordId("ticket-1", next);
        Integer instances = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_instance WHERE tenant_id = ? AND entity_key = ? AND record_id = ?",
                Integer.class,
                TENANT_A,
                "repair-ticket",
                recordId);
        assertEquals(1, instances);
        assertEquals(0, worker.processDue(10));
        Integer stillOne = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_instance WHERE tenant_id = ? AND entity_key = ?",
                Integer.class,
                TENANT_A,
                "repair-ticket");
        assertEquals(1, stillOne);
    }

    @Test
    void disabledOrNotDueScheduleIsSkipped() throws Exception {
        String enabled = readJson(
                mvc.perform(post(OperatorScheduleEndpoint.PATH)
                                .with(asOperator(writer))
                                .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(taskBody("Not yet", "0 0 9 * * *", true, "later")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "scheduleId");
        String disabledId = readJson(
                mvc.perform(post(OperatorScheduleEndpoint.PATH)
                                .with(asOperator(writer))
                                .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(taskBody("Off", "0 0 9 * * *", true, "off")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "scheduleId");
        Instant next = port.find(TENANT_A, disabledId).orElseThrow().nextRunAt();
        mvc.perform(post(OperatorScheduleEndpoint.PATH + "/" + disabledId + "/disable")
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk());

        clock.set(next);
        assertEquals(1, worker.processDue(10));
        assertEquals(1, tasks.submitted.size());
        assertEquals("later", tasks.submitted.get(0).stepName());
        assertFalse(port.find(TENANT_A, disabledId).orElseThrow().enabled());
        assertNull(port.find(TENANT_A, disabledId).orElseThrow().lastRunAt());
        assertEquals(enabled, tasks.submitted.get(0).idempotencyToken().split(":")[1]);
    }

    @Test
    void invalidCronAndMissingWorkflowFailClosed() throws Exception {
        mvc.perform(post(OperatorScheduleEndpoint.PATH)
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskBody("Bad cron", "not-a-cron", true, "step")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("invalid cron expression: not-a-cron"));

        mvc.perform(post(OperatorScheduleEndpoint.PATH)
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workflowBody("Missing machine", "0 0 9 * * *", true, "no-such-workflow", "rec-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("unknown workflow definition: no-such-workflow"));

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM operator_schedule", Integer.class);
        assertEquals(0, count);
    }

    @Test
    void tenantIsolationAndReservedPlatformAreFailClosed() throws Exception {
        mvc.perform(post(OperatorScheduleEndpoint.PATH)
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskBody("Only A", "0 0 9 * * *", true, "only-a")))
                .andExpect(status().isOk());

        mvc.perform(get(OperatorScheduleEndpoint.PATH)
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(get(OperatorScheduleEndpoint.PATH)
                        .with(asOperator(reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isForbidden());

        mvc.perform(get(OperatorScheduleEndpoint.PATH)
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, JdbcSchedulePort.RESERVED_TENANT))
                .andExpect(status().isForbidden());

        mvc.perform(get(OperatorScheduleEndpoint.PATH).with(asOperator(writer)))
                .andExpect(status().isForbidden());
    }

    @Test
    void secondWorkerCannotClaimALiveLeaseOnTheSameDueTick() throws Exception {
        String created = mvc.perform(post(OperatorScheduleEndpoint.PATH)
                        .with(asOperator(writer))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(taskBody("Lease", "0 0 9 * * *", true, "lease-step")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String scheduleId = readJson(created, "scheduleId");
        Instant next = port.find(TENANT_A, scheduleId).orElseThrow().nextRunAt();
        clock.set(next);

        Optional<ScheduleClaim> first = tx.execute(status -> port.claim(scheduleId, "worker-a", clock.instant(), Duration.ofMinutes(2)));
        Optional<ScheduleClaim> second = tx.execute(status -> port.claim(scheduleId, "worker-b", clock.instant(), Duration.ofMinutes(2)));
        assertTrue(first != null && first.isPresent());
        assertTrue(second == null || second.isEmpty());

        ScheduleWorker other = new ScheduleWorker(port, tasks, workflows, tx, clock, 10, "worker-b");
        assertEquals(0, other.processDue(10));
        assertTrue(tasks.submitted.isEmpty());
    }

    private void grant(OperatorPrincipal operator, String tenantId) {
        jdbc.update(
                "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)",
                operator.subjectId(),
                tenantId);
    }

    private static String taskBody(String name, String cron, boolean enabled, String step) {
        return "{\"name\":\""
                + name
                + "\",\"cronExpression\":\""
                + cron
                + "\",\"enabled\":"
                + enabled
                + ",\"action\":\"ENQUEUE_TASK\",\"taskStepName\":\""
                + step
                + "\"}";
    }

    private static String workflowBody(String name, String cron, boolean enabled, String key, String recordId) {
        return "{\"name\":\""
                + name
                + "\",\"cronExpression\":\""
                + cron
                + "\",\"enabled\":"
                + enabled
                + ",\"action\":\"START_WORKFLOW\",\"workflowDefinitionKey\":\""
                + key
                + "\",\"workflowRecordId\":\""
                + recordId
                + "\"}";
    }

    private static String readJson(String json, String field) {
        String needle = "\"" + field + "\":\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            throw new IllegalStateException("missing " + field + " in " + json);
        }
        int start = at + needle.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    private static OperatorPrincipal operator(String slug, String... permissions) {
        return new OperatorPrincipal(
                "op-" + slug, "hash", "id-" + slug, "sub-" + slug, Set.of(permissions), true);
    }

    private static RequestPostProcessor asOperator(OperatorPrincipal operator) {
        return request -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(operator, "n/a", List.of()));
            SecurityContextHolder.setContext(context);
            return request;
        };
    }

    private static final class AdjustableClock extends Clock {
        private Instant instant;

        private AdjustableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class RecordingTaskPort implements TaskMessagePort {
        private final List<TaskCommand> submitted = new CopyOnWriteArrayList<>();
        private final Clock clock;

        private RecordingTaskPort(Clock clock) {
            this.clock = clock;
        }

        @Override
        public TaskRecord submit(TaskCommand command) {
            submitted.add(command);
            return new TaskRecord(
                    "task-" + submitted.size(),
                    command.tenantId(),
                    command.taskKind(),
                    TaskState.PENDING,
                    command.stepName(),
                    0,
                    1,
                    command.modelId(),
                    command.inputDigest(),
                    command.humanConfirmation(),
                    null,
                    clock.instant());
        }

        @Override
        public TaskRecord retry(String tenantId, String taskId, boolean stepSucceeded) {
            throw new UnsupportedOperationException("retry");
        }

        @Override
        public TaskRecord confirm(String tenantId, String taskId) {
            throw new UnsupportedOperationException("confirm");
        }

        @Override
        public int relayPending(int batchSize) {
            throw new UnsupportedOperationException("relayPending");
        }

        @Override
        public OutboxEvent replayDeadLetter(String deadLetterId) {
            throw new UnsupportedOperationException("replayDeadLetter");
        }

        @Override
        public DeadLetter discardDeadLetter(String deadLetterId) {
            throw new UnsupportedOperationException("discardDeadLetter");
        }
    }
}
