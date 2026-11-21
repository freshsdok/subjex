package com.subjex.platform.app.workflow;

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
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * WorkflowCatalogTest — WP4-1 classpath catalog + repair-ticket declaration wire names.
 * 目录加载报修工单 YAML；线名与原硬编码图一致；未知定义与非法流转失败关闭。
 */
@Tag(WorkflowTags.WORKFLOW)
class WorkflowCatalogTest {

    private static final String TENANT = "tenant-wf-41";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T02:00:00Z"), ZoneOffset.UTC);

    private WorkflowCatalog catalog;
    private WorkflowService workflows;
    private OperatorPrincipal writer;

    @BeforeEach
    void setUp() {
        catalog = new WorkflowCatalog();
        DataSource ds = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                TENANT,
                "WF-41");
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
        writer = new OperatorPrincipal(
                "wf-41-writer",
                "",
                "id-wf-41-writer",
                "subject-wf-41",
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
    void catalogLoadsRepairTicketYamlWithHistoricWireNames() {
        WorkflowDefinition def = catalog.require(RepairTicketWorkflowDefinitions.REPAIR_TICKET);
        assertThat(def.definitionKey()).isEqualTo("repair-ticket");
        assertThat(def.entityKey()).isEqualTo("repair-ticket");
        assertThat(def.initialState()).isEqualTo(WorkflowState.OPEN);
        assertThat(def.states())
                .containsExactly(
                        WorkflowState.OPEN,
                        WorkflowState.IN_PROGRESS,
                        WorkflowState.AWAITING_APPROVAL,
                        WorkflowState.APPROVED,
                        WorkflowState.CLOSED);
        assertThat(def.findTransition(WorkflowState.OPEN, WorkflowAction.START_WORK))
                .hasValueSatisfying(t -> {
                    assertThat(t.to()).isEqualTo(WorkflowState.IN_PROGRESS);
                    assertThat(t.requiredPermission()).isEqualTo(OperatorPermission.WORKFLOW_WRITE.permissionName());
                    assertThat(t.capabilityId()).isNull();
                });
        assertThat(def.findTransition(WorkflowState.IN_PROGRESS, WorkflowAction.REQUEST_APPROVAL))
                .hasValueSatisfying(t -> {
                    assertThat(t.to()).isEqualTo(WorkflowState.AWAITING_APPROVAL);
                    assertThat(t.capabilityId()).isEqualTo(CapabilityId.AI_SUMMARIZE_PREVIEW.id());
                });
        assertThat(def.findTransition(WorkflowState.AWAITING_APPROVAL, WorkflowAction.APPROVE))
                .hasValueSatisfying(t -> assertThat(t.to()).isEqualTo(WorkflowState.APPROVED));
        assertThat(def.findTransition(WorkflowState.AWAITING_APPROVAL, WorkflowAction.REJECT))
                .hasValueSatisfying(t -> assertThat(t.to()).isEqualTo(WorkflowState.OPEN));
        assertThat(def.findTransition(WorkflowState.APPROVED, WorkflowAction.CLOSE))
                .hasValueSatisfying(t -> assertThat(t.to()).isEqualTo(WorkflowState.CLOSED));
        assertThat(def.findTransition(WorkflowState.CLOSED, WorkflowAction.START_WORK)).isEmpty();
        assertThat(def.findTransition(WorkflowState.OPEN, WorkflowAction.APPROVE)).isEmpty();
        // Hooks are present on the declaration and applied by the engine in WP4-2.
        // 钩子已写入声明，并由 WP4-2 引擎应用。
        assertThat(def.autoStartOnUpsert()).isTrue();
        assertThat(def.orgScope())
                .hasValueSatisfying(scope -> {
                    assertThat(scope.field()).isEqualTo("organization");
                    assertThat(scope.onActions()).containsExactly("approve", "reject");
                });
        assertThat(def.dualApproval())
                .hasValueSatisfying(dual -> {
                    assertThat(dual.requestAction()).isEqualTo("request_approval");
                    assertThat(dual.consumeActions()).containsExactly("approve", "reject");
                });
    }

    @Test
    void startAndTransitionStillUseHistoricApiWireNames() {
        WorkflowInstance started = workflows.start(
                writer,
                RepairTicketWorkflowDefinitions.REPAIR_TICKET,
                TENANT,
                "repair-ticket",
                "ticket-wp41");
        assertThat(started.definitionKey()).isEqualTo("repair-ticket");
        assertThat(started.currentState()).isEqualTo(WorkflowState.OPEN);
        assertThat(started.currentState().name()).isEqualTo("OPEN");

        WorkflowInstance inProgress =
                workflows.transition(writer, started.instanceId(), WorkflowAction.fromWire("start_work"), null);
        assertThat(inProgress.currentState()).isEqualTo(WorkflowState.IN_PROGRESS);
    }

    @Test
    void unknownDefinitionAndIllegalTransitionFailClosed() {
        assertThatThrownBy(() -> catalog.require("no-such-machine"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown workflow definition");
        assertThatThrownBy(() -> workflows.start(writer, "no-such-machine", TENANT, "repair-ticket", "t-missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown workflow definition");

        WorkflowInstance started = workflows.start(
                writer,
                RepairTicketWorkflowDefinitions.REPAIR_TICKET,
                TENANT,
                "repair-ticket",
                "ticket-illegal");
        assertThatThrownBy(() -> workflows.transition(writer, started.instanceId(), WorkflowAction.APPROVE, null))
                .isInstanceOf(IllegalWorkflowTransitionException.class)
                .hasMessageContaining("OPEN")
                .hasMessageContaining("approve");
        assertThat(workflows.status(writer, started.instanceId()).instance().currentState())
                .isEqualTo(WorkflowState.OPEN);
    }

    @Test
    void readerAcceptsDeclaredTokensAndRejectsBlankOrUnknownKeys() {
        WorkflowDeclarationReader reader = new WorkflowDeclarationReader();
        WorkflowDefinition sample = reader.render(
                """
                definitionKey: token-sample
                initialState: NEW
                transitions:
                  - from: NEW
                    action: finish
                    to: DONE
                    permission: workflow.write
                """);
        assertThat(sample.initialState().wireName()).isEqualTo("NEW");
        assertThat(sample.findTransition(WorkflowState.of("NEW"), WorkflowAction.fromWire("finish")))
                .hasValueSatisfying(t -> assertThat(t.to().wireName()).isEqualTo("DONE"));

        assertThatThrownBy(() -> reader.render("definitionKey: x\n"))
                .isInstanceOf(WorkflowDeclarationRejected.class);
        assertThatThrownBy(() -> reader.render(
                        """
                        definitionKey: bad
                        initialState: OPEN
                        timers: true
                        transitions:
                          - from: OPEN
                            action: close
                            to: CLOSED
                            permission: workflow.write
                        """))
                .isInstanceOf(WorkflowDeclarationRejected.class)
                .hasMessageContaining("unknown workflow key");
    }
}
