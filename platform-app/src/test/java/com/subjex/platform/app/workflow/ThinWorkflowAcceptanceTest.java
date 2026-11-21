package com.subjex.platform.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.subjex.platform.app.capability.CapabilityCatalog;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.JdbcOperatorTenantAccess;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ThinWorkflowAcceptanceTest — WP4-4 formal package acceptance (ADR 0016).
 * This is the named lock for closing WP4. It does not repeat the WP4-3 scenario suite
 * ({@link OrderApprovalWorkflowTest}): it asserts both classpath machines run on the existing
 * {@link WorkflowService} / {@link WorkflowEndpoint} / {@link WorkflowCatalog}, with distinct
 * action tokens, and that {@code order-approval} is not a kernel Java special-case.
 * <p>
 * 正式关包验收：不重复 WP4-3 场景套件。两台 classpath 机器走现有引擎与目录，动作令牌不同，
 * {@code order-approval} 不是内核 Java 特例。双人审批、组织范围失败关闭、审计字段与报修行为
 * 由既有聚焦套件锁定。这仍是薄状态机，不是 Camunda / BPM。
 */
@Tag(WorkflowTags.WORKFLOW)
class ThinWorkflowAcceptanceTest {

    private static final String TENANT = "tenant-wf-44";
    private static final String ORDER = "order-approval";
    private static final String REPAIR = RepairTicketWorkflowDefinitions.REPAIR_TICKET;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T03:20:00Z"), ZoneOffset.UTC);

    private JdbcTemplate jdbc;
    private WorkflowCatalog catalog;
    private WorkflowService workflows;
    private WorkflowEndpoint endpoint;
    private OperatorPrincipal writer;

    @BeforeEach
    void setUp() {
        DataSource ds = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                TENANT,
                "WF-44");
        catalog = new WorkflowCatalog();
        workflows = new WorkflowService(
                new JdbcWorkflowStore(jdbc, tx),
                new JdbcOperatorTenantAccess(jdbc, tx),
                new CapabilityCatalog(),
                new OperatorActionAudit(new JdbcAuditPort(jdbc), CLOCK),
                CLOCK,
                null,
                null,
                null,
                catalog);
        endpoint = new WorkflowEndpoint(workflows);
        writer = new OperatorPrincipal(
                "wf-44-writer",
                "",
                "id-wf-44-writer",
                "subject-wf-44",
                Set.of(
                        OperatorPermission.WORKFLOW_READ.permissionName(),
                        OperatorPermission.WORKFLOW_WRITE.permissionName()),
                true);
        jdbc.update(
                "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)",
                writer.subjectId(),
                TENANT);
    }

    @Test
    void catalogDeclaresBothMachinesWithDistinctTokensAndNoKernelSpecialCase() throws Exception {
        WorkflowDefinition repair = catalog.require(REPAIR);
        WorkflowDefinition order = catalog.require(ORDER);
        assertThat(catalog.findByEntityKey(REPAIR)).hasValue(repair);
        assertThat(catalog.findByEntityKey(ORDER)).hasValue(order);
        assertThat(order.definitionKey()).isEqualTo(ORDER);
        assertThat(order.entityKey()).isNotEqualTo(repair.entityKey());
        assertThat(order.initialState().wireName()).isEqualTo("SUBMITTED");
        assertThat(repair.initialState()).isEqualTo(WorkflowState.OPEN);

        assertThat(order.transitions().stream().map(edge -> edge.action().wireName()))
                .containsExactly("submit", "approve", "reject", "complete")
                .doesNotContain("start_work", "request_approval", "close");
        assertThat(repair.transitions().stream().map(edge -> edge.action().wireName()))
                .containsExactly("start_work", "request_approval", "approve", "reject", "close");

        assertThat(order.autoStartOnUpsert()).isTrue();
        assertThat(order.dualApproval()).isPresent();
        assertThat(order.orgScope()).isPresent();
        assertThat(repair.autoStartOnUpsert()).isTrue();
        assertThat(repair.dualApproval()).isPresent();
        assertThat(repair.orgScope()).isPresent();

        Path mainJava = repoRoot().resolve("platform-app").resolve("src").resolve("main").resolve("java");
        assertThat(mainJava).isDirectory();
        try (Stream<Path> walk = Files.walk(mainJava)) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                assertThat(name).isNotEqualTo("OrderApprovalWorkflowDefinitions.java");
                String source = Files.readString(file, StandardCharsets.UTF_8);
                assertThat(source)
                        .as("production Java must not special-case %s (%s)", ORDER, file)
                        .doesNotContain(ORDER);
            }
        }
        assertThat(endpoint.byEntity(writer, ORDER).definitionKey()).isEqualTo(ORDER);
        assertThat(endpoint.byEntity(writer, REPAIR).definitionKey()).isEqualTo(REPAIR);
    }

    @Test
    void bothMachinesRunOnExistingServiceAndHttpSurface() {
        WorkflowEndpoint.InstanceDocument repair = endpoint.start(
                writer, REPAIR, new WorkflowEndpoint.StartRequest(TENANT, REPAIR, "rt-wp44"));
        assertThat(repair.definitionKey()).isEqualTo(REPAIR);
        assertThat(repair.currentState()).isEqualTo("OPEN");
        WorkflowEndpoint.InstanceDocument inProgress = endpoint.transition(
                writer, repair.instanceId(), new WorkflowEndpoint.TransitionRequest("start_work", null));
        assertThat(inProgress.currentState()).isEqualTo("IN_PROGRESS");
        assertThatThrownBy(() ->
                        endpoint.transition(
                                writer, repair.instanceId(), new WorkflowEndpoint.TransitionRequest("submit", null)))
                .isInstanceOf(IllegalWorkflowTransitionException.class);

        WorkflowEndpoint.InstanceDocument order = endpoint.start(
                writer, ORDER, new WorkflowEndpoint.StartRequest(TENANT, ORDER, "ord-wp44"));
        assertThat(order.definitionKey()).isEqualTo(ORDER);
        assertThat(order.currentState()).isEqualTo("SUBMITTED");
        WorkflowEndpoint.InstanceDocument awaiting = endpoint.transition(
                writer, order.instanceId(), new WorkflowEndpoint.TransitionRequest("submit", null));
        assertThat(awaiting.currentState()).isEqualTo("AWAITING_APPROVAL");
        assertThatThrownBy(() ->
                        endpoint.transition(
                                writer, order.instanceId(), new WorkflowEndpoint.TransitionRequest("request_approval", null)))
                .isInstanceOf(IllegalWorkflowTransitionException.class);

        WorkflowEndpoint.StatusDocument repairStatus = endpoint.getStatus(writer, repair.instanceId());
        WorkflowEndpoint.StatusDocument orderStatus = endpoint.getStatus(writer, order.instanceId());
        assertThat(repairStatus.history()).extracting(WorkflowEndpoint.HistoryDocument::actionName).contains("start", "start_work");
        assertThat(orderStatus.history()).extracting(WorkflowEndpoint.HistoryDocument::actionName).contains("start", "submit");
        assertThat(orderStatus.pendingApproval()).isNotNull();

        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_entry WHERE action_name = ? AND action_target = ?",
                        Integer.class,
                        WorkflowService.AUDIT_START,
                        repair.instanceId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_entry WHERE action_name = ? AND action_target = ?",
                        Integer.class,
                        WorkflowService.AUDIT_START,
                        order.instanceId()))
                .isEqualTo(1);
    }

    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("pom.xml"))
                    && Files.isDirectory(dir.resolve("platform-app"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("cannot locate repo root from " + System.getProperty("user.dir"));
    }
}
