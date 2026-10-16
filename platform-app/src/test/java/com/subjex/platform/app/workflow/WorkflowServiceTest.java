package com.subjex.platform.app.workflow;

import org.junit.jupiter.api.Tag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.subjex.platform.app.capability.CapabilityCatalog;
import com.subjex.platform.app.capability.CapabilityId;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.JdbcOperatorTenantAccess;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * WorkflowServiceTest — WF-1 happy path + illegal transition + Cap catalog hook.
 * 目的：WF-1 顺路路径、非法流转与能力目录挂接。
 */
@Tag(WorkflowTags.WORKFLOW)
class WorkflowServiceTest {

    private static final String TENANT = "tenant-wf-1";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T05:00:00Z"), ZoneOffset.UTC);

    private JdbcTemplate jdbc;
    private WorkflowService workflows;
    private OperatorPrincipal writer;
    private OperatorPrincipal approver;
    private OperatorPrincipal readerOnly;

    @BeforeEach
    void setUp() {
        DataSource ds = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        ensureTenant(TENANT);
        JdbcWorkflowStore store = new JdbcWorkflowStore(jdbc, tx);
        OperatorTenantAccess tenantAccess = new JdbcOperatorTenantAccess(jdbc, tx);
        workflows = new WorkflowService(
                store,
                tenantAccess,
                new CapabilityCatalog(),
                new OperatorActionAudit(new JdbcAuditPort(jdbc), CLOCK),
                CLOCK);
        writer = operator(
                "wf-writer",
                "subject-writer",
                Set.of(
                        OperatorPermission.WORKFLOW_READ.permissionName(),
                        OperatorPermission.WORKFLOW_WRITE.permissionName()));
        approver = operator(
                "wf-approver",
                "subject-approver",
                Set.of(
                        OperatorPermission.WORKFLOW_READ.permissionName(),
                        OperatorPermission.WORKFLOW_APPROVE.permissionName()));
        readerOnly = operator(
                "wf-reader",
                "subject-reader",
                Set.of(OperatorPermission.WORKFLOW_READ.permissionName()));
        grant(writer.subjectId(), TENANT);
        grant(approver.subjectId(), TENANT);
        grant(readerOnly.subjectId(), TENANT);
    }

    @Test
    void happyPathOpenToClosed() {
        WorkflowInstance started = workflows.start(
                writer,
                RepairTicketWorkflowDefinitions.REPAIR_TICKET,
                TENANT,
                "repair-ticket",
                "ticket-1");
        assertThat(started.currentState()).isEqualTo(WorkflowState.OPEN);

        WorkflowInstance inProgress =
                workflows.transition(writer, started.instanceId(), WorkflowAction.START_WORK, "picked up");
        assertThat(inProgress.currentState()).isEqualTo(WorkflowState.IN_PROGRESS);

        WorkflowInstance awaiting = workflows.transition(
                writer, started.instanceId(), WorkflowAction.REQUEST_APPROVAL, "ready for review");
        assertThat(awaiting.currentState()).isEqualTo(WorkflowState.AWAITING_APPROVAL);

        WorkflowService.WorkflowStatusView mid = workflows.status(readerOnly, started.instanceId());
        assertThat(mid.history()).extracting(WorkflowHistoryEntry::actionName)
                .contains("start", "start_work", "request_approval");
        assertThat(mid.history())
                .filteredOn(h -> "request_approval".equals(h.actionName()))
                .extracting(WorkflowHistoryEntry::capabilityId)
                .containsExactly(CapabilityId.AI_SUMMARIZE_PREVIEW.id());
        assertThat(mid.pendingApproval()).isNotNull();
        assertThat(mid.pendingApproval().requestedBySubjectId()).isEqualTo(writer.subjectId());
        assertThat(mid.pendingApproval().approvalState()).isEqualTo(WorkflowApproval.PENDING);

        WorkflowInstance approved =
                workflows.transition(approver, started.instanceId(), WorkflowAction.APPROVE, "lgtm");
        assertThat(approved.currentState()).isEqualTo(WorkflowState.APPROVED);

        WorkflowInstance closed = workflows.transition(writer, started.instanceId(), WorkflowAction.CLOSE, null);
        assertThat(closed.currentState()).isEqualTo(WorkflowState.CLOSED);

        WorkflowService.WorkflowStatusView byRecord =
                workflows.statusByRecord(readerOnly, TENANT, "repair-ticket", "ticket-1");
        assertThat(byRecord.instance().instanceId()).isEqualTo(started.instanceId());
        assertThat(byRecord.instance().currentState()).isEqualTo(WorkflowState.CLOSED);
    }

    @Test
    void illegalTransitionFromOpenToApprove() {
        WorkflowInstance started = workflows.start(
                writer,
                RepairTicketWorkflowDefinitions.REPAIR_TICKET,
                TENANT,
                "repair-ticket",
                "ticket-bad");
        assertThatThrownBy(() -> workflows.transition(approver, started.instanceId(), WorkflowAction.APPROVE, null))
                .isInstanceOf(IllegalWorkflowTransitionException.class)
                .hasMessageContaining("OPEN")
                .hasMessageContaining("approve");
        assertThat(workflows.status(readerOnly, started.instanceId()).instance().currentState())
                .isEqualTo(WorkflowState.OPEN);
    }

    @Test
    void rejectReturnsToOpen() {
        WorkflowInstance started = workflows.start(
                writer,
                RepairTicketWorkflowDefinitions.REPAIR_TICKET,
                TENANT,
                "repair-ticket",
                "ticket-reject");
        workflows.transition(writer, started.instanceId(), WorkflowAction.START_WORK, null);
        workflows.transition(writer, started.instanceId(), WorkflowAction.REQUEST_APPROVAL, null);
        WorkflowInstance rejected =
                workflows.transition(approver, started.instanceId(), WorkflowAction.REJECT, "needs rework");
        assertThat(rejected.currentState()).isEqualTo(WorkflowState.OPEN);
    }

    @Test
    void duplicateStartConflicts() {
        workflows.start(
                writer,
                RepairTicketWorkflowDefinitions.REPAIR_TICKET,
                TENANT,
                "repair-ticket",
                "ticket-dup");
        assertThatThrownBy(() -> workflows.start(
                        writer,
                        RepairTicketWorkflowDefinitions.REPAIR_TICKET,
                        TENANT,
                        "repair-ticket",
                        "ticket-dup"))
                .isInstanceOf(WorkflowAlreadyStartedException.class);
    }

    @Test
    void writerCannotApproveWithoutPermission() {
        WorkflowInstance started = workflows.start(
                writer,
                RepairTicketWorkflowDefinitions.REPAIR_TICKET,
                TENANT,
                "repair-ticket",
                "ticket-perm");
        workflows.transition(writer, started.instanceId(), WorkflowAction.START_WORK, null);
        workflows.transition(writer, started.instanceId(), WorkflowAction.REQUEST_APPROVAL, null);
        assertThatThrownBy(() -> workflows.transition(writer, started.instanceId(), WorkflowAction.APPROVE, null))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }


    @Test
    void selfApproveRefusedNeedsSecondOperator() {
        OperatorPrincipal both = operator(
                "wf-both",
                "subject-both",
                Set.of(
                        OperatorPermission.WORKFLOW_READ.permissionName(),
                        OperatorPermission.WORKFLOW_WRITE.permissionName(),
                        OperatorPermission.WORKFLOW_APPROVE.permissionName()));
        grant(both.subjectId(), TENANT);
        WorkflowInstance started = workflows.start(
                both,
                RepairTicketWorkflowDefinitions.REPAIR_TICKET,
                TENANT,
                "repair-ticket",
                "ticket-self");
        workflows.transition(both, started.instanceId(), WorkflowAction.START_WORK, null);
        workflows.transition(both, started.instanceId(), WorkflowAction.REQUEST_APPROVAL, null);
        assertThatThrownBy(() -> workflows.transition(both, started.instanceId(), WorkflowAction.APPROVE, null))
                .isInstanceOf(WorkflowNeedsSecondApprover.class);
        assertThatThrownBy(() -> workflows.transition(both, started.instanceId(), WorkflowAction.REJECT, null))
                .isInstanceOf(WorkflowNeedsSecondApprover.class);
        assertThat(workflows.status(readerOnly, started.instanceId()).instance().currentState())
                .isEqualTo(WorkflowState.AWAITING_APPROVAL);
    }

    @Test
    void migrationCreatesWorkflowTables() {
        Integer instances = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE LOWER(table_name) = 'workflow_instance'
                """,
                Integer.class);
        Integer history = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE LOWER(table_name) = 'workflow_transition_history'
                """,
                Integer.class);
        assertThat(instances).isGreaterThanOrEqualTo(1);
        assertThat(history).isGreaterThanOrEqualTo(1);
        Integer perms = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM platform_permission
                WHERE permission_name IN ('workflow.read', 'workflow.write', 'workflow.approve')
                """,
                Integer.class);
        assertThat(perms).isEqualTo(3);
    }

    private void ensureTenant(String tenantId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE tenant_id = ?", Integer.class, tenantId);
        if (n == null || n == 0) {
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    tenantId,
                    tenantId);
        }
    }

    private void grant(String subjectId, String tenantId) {
        jdbc.update(
                "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)",
                subjectId,
                tenantId);
    }

    private static OperatorPrincipal operator(String login, String subjectId, Set<String> perms) {
        return new OperatorPrincipal(login, "", "id-" + login, subjectId, perms, true);
    }
}
