package com.subjex.platform.app.workflow;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.subjex.platform.app.capability.CapabilityCatalog;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.JdbcOperatorTenantAccess;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantNotGrantedException;
import com.subjex.platform.app.tenant.TenantIsolationTags;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * WorkflowIsolationTest — WF-4: cross-tenant grant fail-closed on workflow status/transition.
 * 跨租户无授权时工作流状态/流转失败关闭。
 */
@Tag(WorkflowTags.WORKFLOW)
@Tag(TenantIsolationTags.TENANT_ISOLATION)
class WorkflowIsolationTest {

    private static final String TENANT_A = "tenant-wf-iso-a";
    private static final String TENANT_B = "tenant-wf-iso-b";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T06:00:00Z"), ZoneOffset.UTC);

    private JdbcTemplate jdbc;
    private WorkflowService workflows;
    private OperatorPrincipal opA;
    private OperatorPrincipal opB;

    @BeforeEach
    void setUp() {
        DataSource ds = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        ensureTenant(TENANT_A);
        ensureTenant(TENANT_B);
        workflows = new WorkflowService(
                new JdbcWorkflowStore(jdbc, tx),
                new JdbcOperatorTenantAccess(jdbc, tx),
                new CapabilityCatalog(),
                new OperatorActionAudit(new JdbcAuditPort(jdbc), CLOCK),
                CLOCK);
        Set<String> perms = Set.of(
                OperatorPermission.WORKFLOW_READ.permissionName(),
                OperatorPermission.WORKFLOW_WRITE.permissionName(),
                OperatorPermission.WORKFLOW_APPROVE.permissionName());
        opA = new OperatorPrincipal("iso-a", "", "id-a", "subject-iso-a", perms, true);
        opB = new OperatorPrincipal("iso-b", "", "id-b", "subject-iso-b", perms, true);
        jdbc.update(
                "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)",
                opA.subjectId(),
                TENANT_A);
        jdbc.update(
                "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)",
                opB.subjectId(),
                TENANT_B);
    }

    @Test
    void otherTenantCannotReadOrTransition() {
        WorkflowInstance started = workflows.start(
                opA,
                RepairTicketWorkflowDefinitions.REPAIR_TICKET,
                TENANT_A,
                "repair-ticket",
                "iso-1");
        assertThatThrownBy(() -> workflows.status(opB, started.instanceId()))
                .isInstanceOf(OperatorTenantNotGrantedException.class);
        assertThatThrownBy(() -> workflows.transition(opB, started.instanceId(), WorkflowAction.START_WORK, null))
                .isInstanceOf(OperatorTenantNotGrantedException.class);
        assertThatThrownBy(() -> workflows.statusByRecord(opB, TENANT_A, "repair-ticket", "iso-1"))
                .isInstanceOf(OperatorTenantNotGrantedException.class);
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
}
