package com.subjex.platform.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.subjex.entity.declare.EntityCatalog;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.form.render.RenderedForm;
import com.subjex.platform.app.capability.CapabilityCatalog;
import com.subjex.platform.app.declaration.DeclarationKind;
import com.subjex.platform.app.declaration.EffectiveDeclarationService;
import com.subjex.platform.app.entity.GenericEntityStore;
import com.subjex.platform.app.entity.HybridEntityStore;
import com.subjex.platform.app.entity.JdbcHybridEntityStore;
import com.subjex.platform.app.form.FormCatalog;
import com.subjex.platform.app.form.action.EntityRecordUpsertDomainActionHandler;
import com.subjex.platform.app.inbox.JdbcInboxPort;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.organization.JdbcOrganizationStore;
import com.subjex.platform.app.organization.OrganizationScopeResolver;
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
import java.util.Optional;
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
 * OrderApprovalWorkflowTest — WP4-3 official {@code order-approval} example acceptance.
 * Submit / approve / reject / complete run through the classpath declaration and existing
 * {@link WorkflowService} / {@link WorkflowEndpoint}, not a copied repair-ticket FSM.
 * 正式订单审批示例验收：提交、审批、驳回、结束走 classpath 声明与现有引擎，不复制报修状态机。
 */
@Tag(WorkflowTags.WORKFLOW)
class OrderApprovalWorkflowTest {

    private static final String TENANT = "tenant-wf-43";
    private static final String ORDER = "order-approval";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T03:00:00Z"), ZoneOffset.UTC);

    private JdbcTemplate jdbc;
    private WorkflowCatalog catalog;
    private WorkflowService workflows;
    private WorkflowEndpoint endpoint;
    private EntityRecordUpsertDomainActionHandler upsert;
    private RenderedForm orderForm;
    private JdbcOrganizationStore orgs;

    private OperatorPrincipal writer;
    private OperatorPrincipal inScopeApprover;
    private OperatorPrincipal outOfScopeApprover;

    @BeforeEach
    void setUp() {
        DataSource ds = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        ensureTenant(TENANT);
        orgs = new JdbcOrganizationStore(jdbc);
        seedOrgTree();

        catalog = new WorkflowCatalog();
        EntityCatalog entityCatalog = EntityCatalog.load(EntityCatalog.class.getClassLoader());
        RenderedEntity orderEntity = entityCatalog.find(ORDER).orElseThrow();
        orderForm = new FormCatalog().require(ORDER);

        HybridEntityStore hybrid = new JdbcHybridEntityStore(jdbc, CLOCK, new ObjectMapper());
        WorkflowOrgApproverGate gate = new WorkflowOrgApproverGate(
                hybrid, entityCatalog, new OrganizationScopeResolver(orgs), orgs, catalog);
        OperatorTenantAccess tenantAccess = new JdbcOperatorTenantAccess(jdbc, tx);
        EffectiveDeclarationService effective = mock(EffectiveDeclarationService.class);
        when(effective.runtimeEntity(any(), eq(ORDER))).thenReturn(Optional.of(orderEntity));
        when(effective.resolutionSource(any(), eq(DeclarationKind.ENTITY), eq(ORDER)))
                .thenReturn(EffectiveDeclarationService.SOURCE_CLASSPATH);
        when(effective.runtimeEntity(any(), eq(RepairTicketWorkflowDefinitions.REPAIR_TICKET)))
                .thenAnswer(inv -> entityCatalog.find(RepairTicketWorkflowDefinitions.REPAIR_TICKET));

        workflows = new WorkflowService(
                new JdbcWorkflowStore(jdbc, tx),
                tenantAccess,
                new CapabilityCatalog(),
                new OperatorActionAudit(new JdbcAuditPort(jdbc), CLOCK),
                CLOCK,
                gate,
                effective,
                new RepairTicketWorkflowInboxHook(new JdbcInboxPort(jdbc, tx), gate),
                catalog);
        endpoint = new WorkflowEndpoint(workflows);
        upsert = new EntityRecordUpsertDomainActionHandler(
                effective,
                mock(GenericEntityStore.class),
                hybrid,
                PolicyEngineFactory.create("sql", new DenyWhenTenantMissing(), tenantAccess),
                workflows,
                catalog,
                tx);
        writer = operator(
                "writer",
                "sub-writer",
                Set.of(
                        OperatorPermission.WORKFLOW_READ.permissionName(),
                        OperatorPermission.WORKFLOW_WRITE.permissionName(),
                        "page.read"));
        inScopeApprover = operator(
                "in-scope",
                "sub-eng",
                Set.of(
                        OperatorPermission.WORKFLOW_READ.permissionName(),
                        OperatorPermission.WORKFLOW_APPROVE.permissionName()));
        outOfScopeApprover = operator(
                "out-scope",
                "sub-sales",
                Set.of(
                        OperatorPermission.WORKFLOW_READ.permissionName(),
                        OperatorPermission.WORKFLOW_APPROVE.permissionName()));
        grant(writer.subjectId());
        grant(inScopeApprover.subjectId());
        grant(outOfScopeApprover.subjectId());
    }

    @Test
    void classpathDeclarationIsDistinctFromRepairTicket() {
        WorkflowDefinition def = catalog.require(ORDER);
        assertThat(def.definitionKey()).isEqualTo(ORDER);
        assertThat(def.entityKey()).isEqualTo(ORDER);
        assertThat(def.entityKey()).isNotEqualTo(RepairTicketWorkflowDefinitions.REPAIR_TICKET);
        assertThat(def.initialState().wireName()).isEqualTo("SUBMITTED");
        assertThat(def.autoStartOnUpsert()).isTrue();
        assertThat(def.orgScope())
                .hasValueSatisfying(scope -> {
                    assertThat(scope.field()).isEqualTo("organization");
                    assertThat(scope.onActions()).containsExactly("approve", "reject");
                });
        assertThat(def.dualApproval())
                .hasValueSatisfying(dual -> {
                    assertThat(dual.requestAction()).isEqualTo("submit");
                    assertThat(dual.consumeActions()).containsExactly("approve", "reject");
                });
        assertThat(def.transitions())
                .extracting(edge -> edge.action().wireName())
                .containsExactly("submit", "approve", "reject", "complete")
                .doesNotContain("start_work", "request_approval", "close");
        assertThat(def.states().stream().map(WorkflowState::wireName))
                .containsExactly("SUBMITTED", "AWAITING_APPROVAL", "APPROVED", "COMPLETED")
                .doesNotContain("OPEN", "IN_PROGRESS", "CLOSED");

        WorkflowDefinition repair = catalog.require(RepairTicketWorkflowDefinitions.REPAIR_TICKET);
        assertThat(repair.definitionKey()).isEqualTo("repair-ticket");
        assertThat(repair.findTransition(WorkflowState.OPEN, WorkflowAction.START_WORK)).isPresent();
        assertThat(repair.findTransition(WorkflowState.IN_PROGRESS, WorkflowAction.REQUEST_APPROVAL)).isPresent();

        WorkflowEndpoint.DefinitionDocument lookup = endpoint.byEntity(writer, ORDER);
        assertThat(lookup.definitionKey()).isEqualTo(ORDER);
        assertThat(lookup.entityKey()).isEqualTo(ORDER);
        assertThat(lookup.initialState()).isEqualTo("SUBMITTED");
        assertThat(endpoint.byEntity(writer, "repair-ticket").definitionKey()).isEqualTo("repair-ticket");
    }

    @Test
    void submitApproveCompleteViaExistingHttpSurface() {
        String summary = upsert.apply(orderForm, orderValues("ord-happy", "org-team"), TENANT, writer);
        assertThat(summary).isEqualTo("order-approval:ord-happy");

        WorkflowEndpoint.StatusDocument started = endpoint.byRecord(writer, TENANT, ORDER, "ord-happy");
        assertThat(started.instance().definitionKey()).isEqualTo(ORDER);
        assertThat(started.instance().entityKey()).isEqualTo(ORDER);
        assertThat(started.instance().currentState()).isEqualTo("SUBMITTED");
        assertThat(started.legalActions()).extracting(WorkflowEndpoint.LegalActionDocument::action).containsExactly("submit");

        WorkflowEndpoint.InstanceDocument awaiting = endpoint.transition(
                writer, started.instance().instanceId(), new WorkflowEndpoint.TransitionRequest("submit", "please review"));
        assertThat(awaiting.currentState()).isEqualTo("AWAITING_APPROVAL");
        WorkflowEndpoint.StatusDocument mid = endpoint.getStatus(writer, awaiting.instanceId());
        assertThat(mid.pendingApproval()).isNotNull();
        assertThat(mid.pendingApproval().requestedBySubjectId()).isEqualTo(writer.subjectId());
        assertThat(mid.legalActions())
                .extracting(WorkflowEndpoint.LegalActionDocument::action)
                .containsExactlyInAnyOrder("approve", "reject");
        assertThat(mid.legalActions()).allMatch(WorkflowEndpoint.LegalActionDocument::consumeApproval);

        OperatorPrincipal writerWithApprove = operator(
                "writer-appr",
                writer.subjectId(),
                Set.of(
                        OperatorPermission.WORKFLOW_READ.permissionName(),
                        OperatorPermission.WORKFLOW_WRITE.permissionName(),
                        OperatorPermission.WORKFLOW_APPROVE.permissionName()));
        orgs.upsertMembership(writer.subjectId(), "org-team", "ACTIVE");
        assertThatThrownBy(() ->
                        endpoint.transition(
                                writerWithApprove,
                                awaiting.instanceId(),
                                new WorkflowEndpoint.TransitionRequest("approve", null)))
                .isInstanceOf(WorkflowNeedsSecondApprover.class);

        WorkflowEndpoint.InstanceDocument approved = endpoint.transition(
                inScopeApprover, awaiting.instanceId(), new WorkflowEndpoint.TransitionRequest("approve", "ok"));
        assertThat(approved.currentState()).isEqualTo("APPROVED");

        WorkflowEndpoint.InstanceDocument completed = endpoint.transition(
                writer, awaiting.instanceId(), new WorkflowEndpoint.TransitionRequest("complete", "done"));
        assertThat(completed.definitionKey()).isEqualTo(ORDER);
        assertThat(completed.currentState()).isEqualTo("COMPLETED");
        assertThat(endpoint.getStatus(writer, awaiting.instanceId()).legalActions()).isEmpty();

        assertThat(actionNamesFor(WorkflowService.AUDIT_START)).isNotEmpty();
        assertThat(actionNamesFor(WorkflowService.AUDIT_APPROVAL_REQUEST)).isNotEmpty();
        assertThat(actionNamesFor(WorkflowService.AUDIT_APPROVAL_CONSUME)).isNotEmpty();
        assertThat(actionNamesFor(WorkflowService.AUDIT_TRANSITION)).isNotEmpty();
        List<Map<String, Object>> startRows = jdbc.queryForList(
                """
                SELECT entity_key, declaration_version, resolution_source
                FROM audit_entry WHERE action_name = ? AND entity_key = ?
                """,
                WorkflowService.AUDIT_START,
                ORDER);
        assertThat(startRows).isNotEmpty();
        assertThat(((Number) startRows.get(0).get("declaration_version")).intValue()).isEqualTo(1);
        assertThat(startRows.get(0).get("resolution_source"))
                .isEqualTo(EffectiveDeclarationService.SOURCE_CLASSPATH);

        Integer inboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_message WHERE tenant_id = ?", Integer.class, TENANT);
        assertThat(inboxCount).isZero();
    }

    @Test
    void rejectReturnsToSubmittedAndCanBeSubmittedAgain() {
        upsert.apply(orderForm, orderValues("ord-reject", "org-team"), TENANT, writer);
        String instanceId = endpoint.byRecord(writer, TENANT, ORDER, "ord-reject").instance().instanceId();
        endpoint.transition(writer, instanceId, new WorkflowEndpoint.TransitionRequest("submit", null));
        WorkflowEndpoint.InstanceDocument rejected =
                endpoint.transition(inScopeApprover, instanceId, new WorkflowEndpoint.TransitionRequest("reject", "fix"));
        assertThat(rejected.currentState()).isEqualTo("SUBMITTED");
        WorkflowEndpoint.InstanceDocument awaiting =
                endpoint.transition(writer, instanceId, new WorkflowEndpoint.TransitionRequest("submit", "retry"));
        assertThat(awaiting.currentState()).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void illegalTransitionsAndRepairTicketWiresFailClosed() {
        upsert.apply(orderForm, orderValues("ord-illegal", "org-team"), TENANT, writer);
        String instanceId = endpoint.byRecord(writer, TENANT, ORDER, "ord-illegal").instance().instanceId();
        assertThatThrownBy(() ->
                        endpoint.transition(writer, instanceId, new WorkflowEndpoint.TransitionRequest("complete", null)))
                .isInstanceOf(IllegalWorkflowTransitionException.class)
                .hasMessageContaining("complete");
        assertThatThrownBy(() ->
                        endpoint.transition(
                                inScopeApprover, instanceId, new WorkflowEndpoint.TransitionRequest("approve", null)))
                .isInstanceOf(IllegalWorkflowTransitionException.class);
        assertThatThrownBy(() ->
                        endpoint.transition(
                                writer, instanceId, new WorkflowEndpoint.TransitionRequest("start_work", null)))
                .isInstanceOf(IllegalWorkflowTransitionException.class)
                .hasMessageContaining("start_work");
        assertThatThrownBy(() ->
                        endpoint.transition(
                                writer, instanceId, new WorkflowEndpoint.TransitionRequest("request_approval", null)))
                .isInstanceOf(IllegalWorkflowTransitionException.class)
                .hasMessageContaining("request_approval");
        assertThatThrownBy(() ->
                        endpoint.transition(writer, instanceId, new WorkflowEndpoint.TransitionRequest("close", null)))
                .isInstanceOf(IllegalWorkflowTransitionException.class)
                .hasMessageContaining("close");
        assertThat(endpoint.getStatus(writer, instanceId).instance().currentState()).isEqualTo("SUBMITTED");
    }

    @Test
    void orgScopeFailClosedWhenDeclared() {
        upsert.apply(orderForm, orderValues("ord-no-org", null), TENANT, writer);
        String missingId = endpoint.byRecord(writer, TENANT, ORDER, "ord-no-org").instance().instanceId();
        endpoint.transition(writer, missingId, new WorkflowEndpoint.TransitionRequest("submit", null));
        assertThatThrownBy(() ->
                        endpoint.transition(
                                inScopeApprover, missingId, new WorkflowEndpoint.TransitionRequest("approve", null)))
                .isInstanceOf(WorkflowApproverOutOfScope.class);

        upsert.apply(orderForm, orderValues("ord-scope", "org-team"), TENANT, writer);
        String scopedId = endpoint.byRecord(writer, TENANT, ORDER, "ord-scope").instance().instanceId();
        endpoint.transition(writer, scopedId, new WorkflowEndpoint.TransitionRequest("submit", null));
        assertThatThrownBy(() ->
                        endpoint.transition(
                                outOfScopeApprover, scopedId, new WorkflowEndpoint.TransitionRequest("approve", null)))
                .isInstanceOf(WorkflowApproverOutOfScope.class);
        assertThat(endpoint.getStatus(writer, scopedId).instance().currentState()).isEqualTo("AWAITING_APPROVAL");

        WorkflowEndpoint.InstanceDocument approved = endpoint.transition(
                inScopeApprover, scopedId, new WorkflowEndpoint.TransitionRequest("approve", "ok"));
        assertThat(approved.currentState()).isEqualTo("APPROVED");
    }

    @Test
    void repairTicketHistoricWiresStillWorkOnSharedCatalog() {
        WorkflowEndpoint.InstanceDocument started = endpoint.start(
                writer,
                RepairTicketWorkflowDefinitions.REPAIR_TICKET,
                new WorkflowEndpoint.StartRequest(TENANT, "repair-ticket", "rt-still"));
        assertThat(started.definitionKey()).isEqualTo("repair-ticket");
        assertThat(started.currentState()).isEqualTo("OPEN");
        WorkflowEndpoint.InstanceDocument inProgress = endpoint.transition(
                writer, started.instanceId(), new WorkflowEndpoint.TransitionRequest("start_work", null));
        assertThat(inProgress.currentState()).isEqualTo("IN_PROGRESS");
        WorkflowEndpoint.InstanceDocument awaiting = endpoint.transition(
                writer, started.instanceId(), new WorkflowEndpoint.TransitionRequest("request_approval", null));
        assertThat(awaiting.currentState()).isEqualTo("AWAITING_APPROVAL");
    }

    private List<String> actionNamesFor(String actionName) {
        return jdbc.queryForList(
                "SELECT action_name FROM audit_entry WHERE action_name = ? AND entity_key = ?",
                String.class,
                actionName,
                ORDER);
    }

    private static Map<String, Object> orderValues(String id, String organizationId) {
        if (organizationId == null) {
            return Map.of("orderId", id, "title", "Sample order");
        }
        return Map.of("orderId", id, "title", "Sample order", "organization", organizationId);
    }

    private void seedOrgTree() {
        for (String[] row : new String[][] {
            {"sub-eng", "Eng"},
            {"sub-sales", "Sales"},
            {"sub-mgr-team", "MgrTeam"},
            {"sub-mgr-eng", "MgrEng"},
            {"sub-writer", "Writer"}
        }) {
            jdbc.update(
                    "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?,?,?)",
                    row[0],
                    row[1],
                    "PERSON");
        }
        orgs.upsertOrganization("org-root", "Root", "ACTIVE");
        orgs.upsertOrganization("org-eng", "Eng", "ACTIVE");
        orgs.upsertOrganization("org-team", "Team", "ACTIVE");
        orgs.upsertOrganization("org-sales", "Sales", "ACTIVE");
        orgs.upsertTenantOrganization(TENANT, "org-root", "ACTIVE");
        orgs.upsertTenantOrganization(TENANT, "org-eng", "ACTIVE");
        orgs.upsertTenantOrganization(TENANT, "org-team", "ACTIVE");
        orgs.upsertTenantOrganization(TENANT, "org-sales", "ACTIVE");
        orgs.setContainsParent("org-eng", "org-root");
        orgs.setContainsParent("org-team", "org-eng");
        orgs.setContainsParent("org-sales", "org-root");
        orgs.upsertMembership("sub-eng", "org-eng", "ACTIVE");
        orgs.upsertMembership("sub-sales", "org-sales", "ACTIVE");
        orgs.setOrganizationManager("org-team", "sub-mgr-team");
        orgs.setOrganizationManager("org-eng", "sub-mgr-eng");
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

    private static OperatorPrincipal operator(String login, String subjectId, Set<String> perms) {
        return new OperatorPrincipal(login, "", "id-" + login, subjectId, perms, true);
    }
}
