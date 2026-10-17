package com.subjex.platform.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.subjex.entity.declare.EntityCatalog;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.form.render.DomainActionKey;
import com.subjex.form.render.FieldKind;
import com.subjex.form.render.FormField;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.capability.CapabilityCatalog;
import com.subjex.platform.app.declaration.DeclarationKind;
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
 * RepairTicketWorkflowAuditTest — WF-RT-4: workflow.start audit carries instance id + declaration version.
 * 目的：workflow.start 审计含实例 id 与声明版本。
 */
@Tag(WorkflowTags.WORKFLOW)
class RepairTicketWorkflowAuditTest {

    private static final String TENANT = "tenant-wf-rt4";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);

    private JdbcTemplate jdbc;
    private WorkflowService workflows;
    private EntityRecordUpsertDomainActionHandler upsert;
    private OperatorPrincipal operator;
    private EffectiveDeclarationService effective;

    @BeforeEach
    void setUp() {
        DataSource ds = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        ensureTenant(TENANT);
        EntityCatalog catalog = EntityCatalog.load(EntityCatalog.class.getClassLoader());
        RenderedEntity repair =
                catalog.find(RepairTicketWorkflowDefinitions.REPAIR_TICKET).orElseThrow();
        effective = mock(EffectiveDeclarationService.class);
        when(effective.runtimeEntity(any(), eq("repair-ticket"))).thenReturn(java.util.Optional.of(repair));
        when(effective.resolutionSource(any(), eq(DeclarationKind.ENTITY), eq("repair-ticket")))
                .thenReturn(EffectiveDeclarationService.SOURCE_CLASSPATH);
        HybridEntityStore hybrid = new JdbcHybridEntityStore(jdbc, CLOCK, new ObjectMapper());
        OperatorTenantAccess tenantAccess = new JdbcOperatorTenantAccess(jdbc, tx);
        workflows = new WorkflowService(
                new JdbcWorkflowStore(jdbc, tx),
                tenantAccess,
                new CapabilityCatalog(),
                new OperatorActionAudit(new JdbcAuditPort(jdbc), CLOCK),
                CLOCK,
                null,
                effective);
        upsert = new EntityRecordUpsertDomainActionHandler(
                effective,
                mock(GenericEntityStore.class),
                hybrid,
                new DenyWhenTenantMissing(),
                tenantAccess,
                workflows,
                tx);
        operator = new OperatorPrincipal(
                "wf-rt4",
                "hash",
                "id-rt4",
                "subject-rt4",
                Set.of(OperatorPermission.WORKFLOW_READ.permissionName(), "page.read"),
                true);
        grant(operator.subjectId());
    }

    @Test
    void autoStartWritesWorkflowStartAuditWithInstanceAndDeclarationVersion() {
        upsert.apply(repairForm(), ticketValues("aud-1"), TENANT, operator);
        WorkflowInstance instance =
                workflows.statusByRecord(operator, TENANT, "repair-ticket", "aud-1").instance();

        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT tenant_id, action_name, action_target, entity_key, declaration_version, resolution_source
                FROM audit_entry
                WHERE action_name = ?
                ORDER BY occurred_at DESC
                """,
                WorkflowService.AUDIT_START);
        assertThat(rows).isNotEmpty();
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("tenant_id")).isEqualTo(TENANT);
        assertThat(row.get("action_target")).isEqualTo(instance.instanceId());
        assertThat(row.get("entity_key")).isEqualTo("repair-ticket");
        assertThat(((Number) row.get("declaration_version")).intValue()).isEqualTo(1);
        assertThat(row.get("resolution_source")).isEqualTo(EffectiveDeclarationService.SOURCE_CLASSPATH);
    }

    @Test
    void explicitStartAlsoLinksDeclarationWhenResolverPresent() {
        WorkflowInstance started = workflows.start(
                operatorWithWrite(),
                RepairTicketWorkflowDefinitions.REPAIR_TICKET,
                TENANT,
                "repair-ticket",
                "aud-2");
        Map<String, Object> row = jdbc.queryForMap(
                """
                SELECT tenant_id, action_target, entity_key, declaration_version, resolution_source
                FROM audit_entry
                WHERE action_name = ? AND action_target = ?
                """,
                WorkflowService.AUDIT_START,
                started.instanceId());
        assertThat(row.get("tenant_id")).isEqualTo(TENANT);
        assertThat(row.get("entity_key")).isEqualTo("repair-ticket");
        assertThat(((Number) row.get("declaration_version")).intValue()).isEqualTo(1);
        assertThat(row.get("resolution_source")).isEqualTo("classpath");
    }

    private OperatorPrincipal operatorWithWrite() {
        OperatorPrincipal w = new OperatorPrincipal(
                "wf-rt4-w",
                "hash",
                "id-rt4-w",
                "subject-rt4-w",
                Set.of(
                        OperatorPermission.WORKFLOW_READ.permissionName(),
                        OperatorPermission.WORKFLOW_WRITE.permissionName()),
                true);
        grant(w.subjectId());
        return w;
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

    private void grant(String subjectId) {
        jdbc.update(
                "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)",
                subjectId,
                TENANT);
    }
}
