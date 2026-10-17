package com.subjex.platform.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.subjex.entity.declare.EntityCatalog;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.platform.app.capability.CapabilityCatalog;
import com.subjex.platform.app.entity.HybridEntityStore;
import com.subjex.platform.app.entity.JdbcHybridEntityStore;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.organization.JdbcOrganizationStore;
import com.subjex.platform.app.organization.OrganizationScopeResolver;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.JdbcOperatorTenantAccess;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * RepairTicketOrgApproverTest — WF-RT-3: in-scope approve OK; out-of-scope denied; self still denied.
 * 目的：范围内审批通过；范围外拒绝；自批仍拒绝。
 */
@Tag(WorkflowTags.WORKFLOW)
class RepairTicketOrgApproverTest {

    private static final String TENANT = "tenant-wf-rt3";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T09:00:00Z"), ZoneOffset.UTC);

    private JdbcTemplate jdbc;
    private WorkflowService workflows;
    private HybridEntityStore hybrid;
    private RenderedEntity repairEntity;
    private JdbcOrganizationStore orgs;
    private RepairTicketOrgApproverGate gate;

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
        EntityCatalog catalog = EntityCatalog.load(EntityCatalog.class.getClassLoader());
        repairEntity = catalog.find(RepairTicketWorkflowDefinitions.REPAIR_TICKET).orElseThrow();
        hybrid = new JdbcHybridEntityStore(jdbc, CLOCK, new ObjectMapper());
        gate = new RepairTicketOrgApproverGate(
                hybrid, catalog, new OrganizationScopeResolver(orgs), orgs);
        JdbcWorkflowStore store = new JdbcWorkflowStore(jdbc, tx);
        OperatorTenantAccess tenantAccess = new JdbcOperatorTenantAccess(jdbc, tx);
        workflows = new WorkflowService(
                store,
                tenantAccess,
                new CapabilityCatalog(),
                new OperatorActionAudit(new JdbcAuditPort(jdbc), CLOCK),
                CLOCK,
                gate);
        writer = operator(
                "writer",
                "sub-writer",
                Set.of(
                        OperatorPermission.WORKFLOW_READ.permissionName(),
                        OperatorPermission.WORKFLOW_WRITE.permissionName()));
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
    void inScopeSecondPersonCanApprove() {
        saveTicket("t-in", "org-team");
        WorkflowInstance started = workflows.start(
                writer, RepairTicketWorkflowDefinitions.REPAIR_TICKET, TENANT, "repair-ticket", "t-in");
        workflows.transition(writer, started.instanceId(), WorkflowAction.START_WORK, null);
        workflows.transition(writer, started.instanceId(), WorkflowAction.REQUEST_APPROVAL, null);

        WorkflowService.WorkflowStatusView mid = workflows.status(writer, started.instanceId());
        assertThat(mid.approvalCandidateSubjectIds()).contains("sub-mgr-team", "sub-mgr-eng");

        WorkflowInstance approved =
                workflows.transition(inScopeApprover, started.instanceId(), WorkflowAction.APPROVE, "ok");
        assertThat(approved.currentState()).isEqualTo(WorkflowState.APPROVED);
    }

    @Test
    void outOfScopeApproverDenied() {
        saveTicket("t-out", "org-team");
        WorkflowInstance started = workflows.start(
                writer, RepairTicketWorkflowDefinitions.REPAIR_TICKET, TENANT, "repair-ticket", "t-out");
        workflows.transition(writer, started.instanceId(), WorkflowAction.START_WORK, null);
        workflows.transition(writer, started.instanceId(), WorkflowAction.REQUEST_APPROVAL, null);

        assertThatThrownBy(
                        () -> workflows.transition(
                                outOfScopeApprover, started.instanceId(), WorkflowAction.APPROVE, null))
                .isInstanceOf(WorkflowApproverOutOfScope.class);
        assertThat(workflows.status(writer, started.instanceId()).instance().currentState())
                .isEqualTo(WorkflowState.AWAITING_APPROVAL);
    }

    @Test
    void selfApproveStillDeniedEvenWhenInScope() {
        // Writer is also member of org-team → in scope, but dual-approve still refuses.
        // 写者也是 org-team 成员→在范围内，但双人批仍拒。
        orgs.upsertMembership("sub-writer", "org-team", "ACTIVE");
        saveTicket("t-self", "org-team");
        OperatorPrincipal writerWithApprove = operator(
                "writer-appr",
                "sub-writer",
                Set.of(
                        OperatorPermission.WORKFLOW_READ.permissionName(),
                        OperatorPermission.WORKFLOW_WRITE.permissionName(),
                        OperatorPermission.WORKFLOW_APPROVE.permissionName()));
        WorkflowInstance started = workflows.start(
                writerWithApprove,
                RepairTicketWorkflowDefinitions.REPAIR_TICKET,
                TENANT,
                "repair-ticket",
                "t-self");
        workflows.transition(writerWithApprove, started.instanceId(), WorkflowAction.START_WORK, null);
        workflows.transition(writerWithApprove, started.instanceId(), WorkflowAction.REQUEST_APPROVAL, null);
        assertThatThrownBy(
                        () -> workflows.transition(
                                writerWithApprove, started.instanceId(), WorkflowAction.APPROVE, null))
                .isInstanceOf(WorkflowNeedsSecondApprover.class);
    }

    @Test
    void missingOrganizationFailsClosedOnApprove() {
        saveTicket("t-no-org", null);
        WorkflowInstance started = workflows.start(
                writer, RepairTicketWorkflowDefinitions.REPAIR_TICKET, TENANT, "repair-ticket", "t-no-org");
        workflows.transition(writer, started.instanceId(), WorkflowAction.START_WORK, null);
        workflows.transition(writer, started.instanceId(), WorkflowAction.REQUEST_APPROVAL, null);
        assertThatThrownBy(
                        () -> workflows.transition(
                                inScopeApprover, started.instanceId(), WorkflowAction.APPROVE, null))
                .isInstanceOf(WorkflowApproverOutOfScope.class);
    }

    private void seedOrgTree() {
        jdbc.update(
                "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?,?,?)",
                "sub-eng",
                "Eng",
                "PERSON");
        jdbc.update(
                "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?,?,?)",
                "sub-sales",
                "Sales",
                "PERSON");
        jdbc.update(
                "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?,?,?)",
                "sub-mgr-team",
                "MgrTeam",
                "PERSON");
        jdbc.update(
                "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?,?,?)",
                "sub-mgr-eng",
                "MgrEng",
                "PERSON");
        jdbc.update(
                "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?,?,?)",
                "sub-writer",
                "Writer",
                "PERSON");
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

    private void saveTicket(String ticketId, String organizationId) {
        Map<String, Object> values = organizationId == null
                ? Map.of(
                        "ticketId", ticketId,
                        "title", "Leak",
                        "location", "A1",
                        "urgency", "medium")
                : Map.of(
                        "ticketId", ticketId,
                        "title", "Leak",
                        "location", "A1",
                        "urgency", "medium",
                        "organization", organizationId);
        hybrid.save(repairEntity, values, TENANT);
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
