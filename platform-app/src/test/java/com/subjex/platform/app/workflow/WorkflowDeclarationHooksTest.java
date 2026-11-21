package com.subjex.platform.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.subjex.entity.declare.EntityCatalog;
import com.subjex.entity.declare.EntityRenderer;
import com.subjex.entity.declare.RenderedEntity;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * WorkflowDeclarationHooksTest — WP4-2: auto-start, dual-approve, and org-scope follow the declaration,
 * not the repair-ticket key. Fixture {@code review-case} is test-only (not the official order-approval example).
 * 自动启动、双人审批与组织范围跟声明走，而不是写死 repair-ticket。review-case 仅为夹具，不是正式示例。
 */
@Tag(WorkflowTags.WORKFLOW)
class WorkflowDeclarationHooksTest {

    private static final String TENANT = "tenant-wf-42";
    private static final String REVIEW = "review-case";
    private static final String IDLE = "idle-case";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T02:30:00Z"), ZoneOffset.UTC);

    private static final String REVIEW_WORKFLOW_YAML =
            """
            definitionKey: review-case
            entityKey: review-case
            version: 1
            initialState: SUBMITTED
            autoStartOnUpsert: true
            orgScope:
              field: organization
              onActions: [approve, reject]
            dualApproval:
              requestAction: submit
              consumeActions: [approve, reject]
            transitions:
              - from: SUBMITTED
                action: submit
                to: AWAITING_APPROVAL
                permission: workflow.write
              - from: AWAITING_APPROVAL
                action: approve
                to: APPROVED
                permission: workflow.approve
              - from: AWAITING_APPROVAL
                action: reject
                to: SUBMITTED
                permission: workflow.approve
              - from: APPROVED
                action: complete
                to: COMPLETED
                permission: workflow.write
            """;

    private static final String IDLE_WORKFLOW_YAML =
            """
            definitionKey: idle-case
            entityKey: idle-case
            version: 1
            initialState: DRAFT
            autoStartOnUpsert: false
            transitions:
              - from: DRAFT
                action: open
                to: OPEN
                permission: workflow.write
            """;

    private static final String REVIEW_ENTITY_YAML =
            """
            entityKey: review-case
            tableName: review_case
            version: 1
            permission: page.read
            tenantScoped: true
            storageMode: hybrid
            fields:
              - name: caseId
                kind: text
                required: true
                maxLength: 64
              - name: title
                kind: text
                required: true
                maxLength: 200
              - name: organization
                kind: organizationRef
                required: false
                maxLength: 64
            """;

    private static final String IDLE_ENTITY_YAML =
            """
            entityKey: idle-case
            tableName: idle_case
            version: 1
            permission: page.read
            tenantScoped: true
            storageMode: hybrid
            fields:
              - name: caseId
                kind: text
                required: true
                maxLength: 64
              - name: title
                kind: text
                required: true
                maxLength: 200
            """;

    private JdbcTemplate jdbc;
    private WorkflowCatalog catalog;
    private WorkflowService workflows;
    private WorkflowEndpoint endpoint;
    private EntityRecordUpsertDomainActionHandler upsert;
    private HybridEntityStore hybrid;
    private RenderedEntity reviewEntity;
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

        EntityRenderer renderer = new EntityRenderer();
        reviewEntity = renderer.render(REVIEW_ENTITY_YAML);
        RenderedEntity idleEntity = renderer.render(IDLE_ENTITY_YAML);
        List<RenderedEntity> entities = new ArrayList<>(EntityCatalog.load(EntityCatalog.class.getClassLoader()).all());
        entities.add(reviewEntity);
        entities.add(idleEntity);
        EntityCatalog entityCatalog = EntityCatalog.of(entities);

        WorkflowDeclarationReader reader = new WorkflowDeclarationReader();
        Map<String, WorkflowDefinition> byKey = new LinkedHashMap<>();
        for (WorkflowDefinition definition : new WorkflowCatalog().list()) {
            byKey.put(definition.definitionKey(), definition);
        }
        byKey.put(REVIEW, reader.render(REVIEW_WORKFLOW_YAML));
        byKey.put(IDLE, reader.render(IDLE_WORKFLOW_YAML));
        catalog = new WorkflowCatalog(byKey);

        hybrid = new JdbcHybridEntityStore(jdbc, CLOCK, new ObjectMapper());
        WorkflowOrgApproverGate gate = new WorkflowOrgApproverGate(
                hybrid, entityCatalog, new OrganizationScopeResolver(orgs), orgs, catalog);
        OperatorTenantAccess tenantAccess = new JdbcOperatorTenantAccess(jdbc, tx);
        EffectiveDeclarationService effective = mock(EffectiveDeclarationService.class);
        when(effective.runtimeEntity(any(), eq(REVIEW))).thenReturn(Optional.of(reviewEntity));
        when(effective.runtimeEntity(any(), eq(IDLE))).thenReturn(Optional.of(idleEntity));
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
                null,
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
    void reviewCaseUpsertAutoStartsAtDeclaredInitialState() {
        String summary = upsert.apply(reviewForm(), reviewValues("rc-1", "org-team"), TENANT, writer);
        assertThat(summary).isEqualTo("review-case:rc-1");
        WorkflowService.WorkflowStatusView status = workflows.statusByRecord(writer, TENANT, REVIEW, "rc-1");
        assertThat(status.instance().definitionKey()).isEqualTo(REVIEW);
        assertThat(status.instance().entityKey()).isEqualTo(REVIEW);
        assertThat(status.instance().currentState().wireName()).isEqualTo("SUBMITTED");
        assertThat(status.legalActions())
                .extracting(WorkflowService.LegalAction::action)
                .containsExactly("submit");
    }

    @Test
    void idleCaseDoesNotAutoStartWhenDeclarationDisablesIt() {
        String summary = upsert.apply(idleForm(), Map.of("caseId", "idle-1", "title", "Parked"), TENANT, writer);
        assertThat(summary).isEqualTo("idle-case:idle-1");
        assertThatThrownBy(() -> workflows.statusByRecord(writer, TENANT, IDLE, "idle-1"))
                .isInstanceOf(WorkflowNotFoundException.class);
        assertThatThrownBy(() -> workflows.startIfAbsentAfterUpsert(writer, TENANT, IDLE, "idle-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no auto-start workflow");
        WorkflowInstance started = workflows.start(writer, IDLE, TENANT, IDLE, "idle-1");
        assertThat(started.definitionKey()).isEqualTo(IDLE);
        assertThat(started.currentState().wireName()).isEqualTo("DRAFT");
    }

    @Test
    void dualApprovalFollowsDeclaredRequestActionNotRepairTicketWire() {
        upsert.apply(reviewForm(), reviewValues("rc-dual", "org-team"), TENANT, writer);
        WorkflowInstance started = workflows.statusByRecord(writer, TENANT, REVIEW, "rc-dual").instance();
        assertThatThrownBy(() ->
                        workflows.transition(writer, started.instanceId(), WorkflowAction.fromWire("request_approval"), null))
                .isInstanceOf(IllegalWorkflowTransitionException.class)
                .hasMessageContaining("request_approval");

        WorkflowInstance awaiting =
                workflows.transition(writer, started.instanceId(), WorkflowAction.fromWire("submit"), null);
        assertThat(awaiting.currentState().wireName()).isEqualTo("AWAITING_APPROVAL");
        WorkflowService.WorkflowStatusView mid = workflows.status(writer, started.instanceId());
        assertThat(mid.pendingApproval()).isNotNull();
        assertThat(mid.pendingApproval().requestedBySubjectId()).isEqualTo(writer.subjectId());
        assertThat(mid.legalActions())
                .anySatisfy(action -> {
                    assertThat(action.action()).isEqualTo("approve");
                    assertThat(action.consumeApproval()).isTrue();
                })
                .anySatisfy(action -> {
                    assertThat(action.action()).isEqualTo("reject");
                    assertThat(action.consumeApproval()).isTrue();
                });

        OperatorPrincipal writerWithApprove = operator(
                "writer-appr",
                writer.subjectId(),
                Set.of(
                        OperatorPermission.WORKFLOW_READ.permissionName(),
                        OperatorPermission.WORKFLOW_WRITE.permissionName(),
                        OperatorPermission.WORKFLOW_APPROVE.permissionName()));
        grant(writerWithApprove.subjectId());
        orgs.upsertMembership(writer.subjectId(), "org-team", "ACTIVE");
        assertThatThrownBy(() ->
                        workflows.transition(writerWithApprove, started.instanceId(), WorkflowAction.APPROVE, null))
                .isInstanceOf(WorkflowNeedsSecondApprover.class);

        WorkflowInstance approved =
                workflows.transition(inScopeApprover, started.instanceId(), WorkflowAction.APPROVE, "ok");
        assertThat(approved.currentState().wireName()).isEqualTo("APPROVED");
    }

    @Test
    void orgScopeFailClosedFollowsDeclarationOnReviewCase() {
        upsert.apply(reviewForm(), reviewValues("rc-no-org", null), TENANT, writer);
        WorkflowInstance missingOrg = workflows.statusByRecord(writer, TENANT, REVIEW, "rc-no-org").instance();
        workflows.transition(writer, missingOrg.instanceId(), WorkflowAction.fromWire("submit"), null);
        assertThatThrownBy(() ->
                        workflows.transition(inScopeApprover, missingOrg.instanceId(), WorkflowAction.APPROVE, null))
                .isInstanceOf(WorkflowApproverOutOfScope.class);

        upsert.apply(reviewForm(), reviewValues("rc-scope", "org-team"), TENANT, writer);
        WorkflowInstance started = workflows.statusByRecord(writer, TENANT, REVIEW, "rc-scope").instance();
        workflows.transition(writer, started.instanceId(), WorkflowAction.fromWire("submit"), null);
        assertThatThrownBy(() ->
                        workflows.transition(outOfScopeApprover, started.instanceId(), WorkflowAction.APPROVE, null))
                .isInstanceOf(WorkflowApproverOutOfScope.class);
        assertThat(workflows.status(writer, started.instanceId()).instance().currentState().wireName())
                .isEqualTo("AWAITING_APPROVAL");

        WorkflowInstance approved =
                workflows.transition(inScopeApprover, started.instanceId(), WorkflowAction.APPROVE, "ok");
        assertThat(approved.currentState().wireName()).isEqualTo("APPROVED");
    }

    @Test
    void lookupByEntityKeyDoesNotForceRepairTicket() {
        assertThat(catalog.findByEntityKey(REVIEW))
                .hasValueSatisfying(def -> {
                    assertThat(def.definitionKey()).isEqualTo(REVIEW);
                    assertThat(def.definitionKey()).isNotEqualTo(RepairTicketWorkflowDefinitions.REPAIR_TICKET);
                    assertThat(def.autoStartOnUpsert()).isTrue();
                });
        assertThat(catalog.findByEntityKey(IDLE))
                .hasValueSatisfying(def -> assertThat(def.autoStartOnUpsert()).isFalse());
        assertThat(catalog.findAutoStart(IDLE)).isEmpty();
        assertThat(catalog.findByEntityKey("demo-ticket")).isEmpty();
        assertThat(catalog.findByEntityKey("repair-ticket"))
                .hasValueSatisfying(def -> assertThat(def.definitionKey()).isEqualTo("repair-ticket"));

        WorkflowEndpoint.DefinitionDocument review = endpoint.byEntity(writer, REVIEW);
        assertThat(review.definitionKey()).isEqualTo(REVIEW);
        assertThat(review.entityKey()).isEqualTo(REVIEW);
        assertThat(review.initialState()).isEqualTo("SUBMITTED");
        assertThat(review.autoStartOnUpsert()).isTrue();

        WorkflowEndpoint.DefinitionDocument repair = endpoint.byEntity(writer, "repair-ticket");
        assertThat(repair.definitionKey()).isEqualTo("repair-ticket");

        assertThatThrownBy(() -> endpoint.byEntity(writer, "demo-ticket"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void repairTicketHistoricWireNamesStillWorkOnSharedCatalog() {
        WorkflowInstance started = workflows.start(
                writer,
                RepairTicketWorkflowDefinitions.REPAIR_TICKET,
                TENANT,
                "repair-ticket",
                "rt-shared");
        WorkflowInstance inProgress =
                workflows.transition(writer, started.instanceId(), WorkflowAction.START_WORK, null);
        assertThat(inProgress.currentState()).isEqualTo(WorkflowState.IN_PROGRESS);
        WorkflowInstance awaiting =
                workflows.transition(writer, started.instanceId(), WorkflowAction.REQUEST_APPROVAL, null);
        assertThat(awaiting.currentState()).isEqualTo(WorkflowState.AWAITING_APPROVAL);
        WorkflowService.WorkflowStatusView status = workflows.status(writer, started.instanceId());
        assertThat(status.legalActions())
                .extracting(WorkflowService.LegalAction::action)
                .containsExactlyInAnyOrder("approve", "reject");
    }

    @Test
    void duplicateEntityKeyFailsClosedAtCatalogLoad() {
        WorkflowDefinition review = catalog.require(REVIEW);
        Map<String, WorkflowDefinition> clash = new LinkedHashMap<>();
        clash.put(review.definitionKey(), review);
        clash.put(
                "review-case-alias",
                new WorkflowDeclarationReader()
                        .render(
                                """
                                definitionKey: review-case-alias
                                entityKey: review-case
                                initialState: SUBMITTED
                                transitions:
                                  - from: SUBMITTED
                                    action: submit
                                    to: DONE
                                    permission: workflow.write
                                """));
        assertThatThrownBy(() -> new WorkflowCatalog(clash))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate workflow entityKey")
                .hasMessageContaining("review-case");
    }

    private static RenderedForm reviewForm() {
        return new RenderedForm(
                REVIEW,
                "Review case",
                "复核单",
                1,
                "page.read",
                true,
                DomainActionKey.ENTITY_RECORD_UPSERT,
                REVIEW,
                "Review",
                List.of(
                        new FormField("caseId", FieldKind.TEXT, true, null, null, 64, List.of()),
                        new FormField("title", FieldKind.TEXT, true, null, null, 200, List.of()),
                        new FormField("organization", FieldKind.ORGANIZATION_REF, false, null, null, 64, List.of())),
                List.of());
    }

    private static RenderedForm idleForm() {
        return new RenderedForm(
                IDLE,
                "Idle case",
                "闲置单",
                1,
                "page.read",
                true,
                DomainActionKey.ENTITY_RECORD_UPSERT,
                IDLE,
                "Idle",
                List.of(
                        new FormField("caseId", FieldKind.TEXT, true, null, null, 64, List.of()),
                        new FormField("title", FieldKind.TEXT, true, null, null, 200, List.of())),
                List.of());
    }

    private static Map<String, Object> reviewValues(String id, String organizationId) {
        if (organizationId == null) {
            return Map.of("caseId", id, "title", "Review");
        }
        return Map.of("caseId", id, "title", "Review", "organization", organizationId);
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
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM operator_tenant_grant WHERE subject_id = ? AND tenant_id = ?",
                Integer.class,
                subjectId,
                TENANT);
        if (n == null || n == 0) {
            jdbc.update(
                    "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)",
                    subjectId,
                    TENANT);
        }
    }

    private static OperatorPrincipal operator(String login, String subjectId, Set<String> perms) {
        return new OperatorPrincipal(login, "", "id-" + login, subjectId, perms, true);
    }
}
