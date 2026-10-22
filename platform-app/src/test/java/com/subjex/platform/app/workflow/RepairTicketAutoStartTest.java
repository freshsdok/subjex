package com.subjex.platform.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.subjex.entity.declare.EntityCatalog;
import com.subjex.form.render.DomainActionKey;
import com.subjex.form.render.FieldKind;
import com.subjex.form.render.FormField;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.capability.CapabilityCatalog;
import com.subjex.platform.app.declaration.EffectiveDeclarationService;
import com.subjex.platform.app.entity.GenericEntityStore;
import com.subjex.platform.app.entity.HybridEntityStore;
import com.subjex.platform.app.entity.JdbcHybridEntityStore;
import com.subjex.platform.app.form.action.EntityRecordUpsertDomainActionHandler;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.JdbcOperatorTenantAccess;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.PolicyEngineFactory;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * RepairTicketAutoStartTest — WF-RT-2: form upsert auto-starts OPEN instance; second upsert is idempotent.
 * 目的：表单 upsert 自动起 OPEN 实例；再次提交不重复起实例。
 */
@Tag(WorkflowTags.WORKFLOW)
class RepairTicketAutoStartTest {

    private static final String TENANT = "tenant-wf-rt2";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T08:00:00Z"), ZoneOffset.UTC);

    private JdbcTemplate jdbc;
    private WorkflowService workflows;
    private EntityRecordUpsertDomainActionHandler upsert;
    private OperatorPrincipal operator;

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
        HybridEntityStore hybrid =
                new JdbcHybridEntityStore(jdbc, CLOCK, new ObjectMapper());
        EntityCatalog catalog = EntityCatalog.load(EntityCatalog.class.getClassLoader());
        EffectiveDeclarationService effective = mock(EffectiveDeclarationService.class);
        when(effective.runtimeEntity(any(), eq(RepairTicketWorkflowDefinitions.REPAIR_TICKET)))
                .thenAnswer(inv -> catalog.find(RepairTicketWorkflowDefinitions.REPAIR_TICKET));
        upsert = new EntityRecordUpsertDomainActionHandler(
                effective,
                mock(GenericEntityStore.class),
                hybrid,
                PolicyEngineFactory.create("sql", new DenyWhenTenantMissing(), tenantAccess),
                workflows,
                tx);
        // page.read only — auto-start must not require workflow.write
        // 仅 page.read — 自动起实例不得再要 workflow.write
        operator = new OperatorPrincipal(
                "wf-rt2",
                "hash",
                "id-rt2",
                "subject-rt2",
                Set.of(OperatorPermission.WORKFLOW_READ.permissionName(), "page.read"),
                true);
        grant(operator.subjectId(), TENANT);
    }

    @Test
    void upsertStartsOpenInstance() {
        String summary = upsert.apply(repairForm(), ticketValues("rt-1"), TENANT, operator);
        assertThat(summary).isEqualTo("repair-ticket:rt-1");
        WorkflowService.WorkflowStatusView status =
                workflows.statusByRecord(operator, TENANT, "repair-ticket", "rt-1");
        assertThat(status.instance().currentState()).isEqualTo(WorkflowState.OPEN);
        assertThat(status.instance().definitionKey()).isEqualTo("repair-ticket");
        assertThat(status.history()).extracting(WorkflowHistoryEntry::actionName).containsExactly("start");
    }

    @Test
    void secondUpsertDoesNotDuplicateInstance() {
        upsert.apply(repairForm(), ticketValues("rt-2"), TENANT, operator);
        WorkflowInstance first =
                workflows.statusByRecord(operator, TENANT, "repair-ticket", "rt-2").instance();
        upsert.apply(
                repairForm(),
                Map.of(
                        "ticketId", "rt-2",
                        "title", "Updated",
                        "location", "B1",
                        "urgency", "high"),
                TENANT,
                operator);
        WorkflowInstance second =
                workflows.statusByRecord(operator, TENANT, "repair-ticket", "rt-2").instance();
        assertThat(second.instanceId()).isEqualTo(first.instanceId());
        assertThat(second.currentState()).isEqualTo(WorkflowState.OPEN);
        long startCount = workflows.statusByRecord(operator, TENANT, "repair-ticket", "rt-2").history().stream()
                .filter(h -> "start".equals(h.actionName()))
                .count();
        assertThat(startCount).isEqualTo(1);
    }

    @Test
    void startIfAbsentAfterUpsertIsIdempotentWithoutWritePermission() {
        WorkflowInstance a = workflows.startIfAbsentAfterUpsert(operator, TENANT, "repair-ticket", "rt-3");
        WorkflowInstance b = workflows.startIfAbsentAfterUpsert(operator, TENANT, "repair-ticket", "rt-3");
        assertThat(b.instanceId()).isEqualTo(a.instanceId());
    }

    @Test
    void upsertWithoutTenantFailsClosed() {
        assertThatThrownBy(() -> upsert.apply(repairForm(), ticketValues("rt-4"), null, operator))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> workflows.statusByRecord(operator, TENANT, "repair-ticket", "rt-4"))
                .isInstanceOf(WorkflowNotFoundException.class);
    }

    private static RenderedForm repairForm() {
        return new RenderedForm(
                "repair-ticket",
                "Repair ticket",
                "报修单",
                1,
                "page.read",
                true,
                DomainActionKey.ENTITY_RECORD_UPSERT,
                "repair-ticket",
                "Repair",
                List.of(
                        new FormField("ticketId", FieldKind.TEXT, true, null, null, 64, List.of()),
                        new FormField("title", FieldKind.TEXT, true, null, null, 200, List.of()),
                        new FormField("location", FieldKind.TEXT, true, null, null, 200, List.of()),
                        new FormField(
                                "urgency",
                                FieldKind.ENUM,
                                true,
                                null,
                                null,
                                32,
                                List.of("low", "medium", "high", "critical"))),
                List.of());
    }

    private static Map<String, Object> ticketValues(String id) {
        return Map.of("ticketId", id, "title", "Leak", "location", "A1", "urgency", "medium");
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
}

