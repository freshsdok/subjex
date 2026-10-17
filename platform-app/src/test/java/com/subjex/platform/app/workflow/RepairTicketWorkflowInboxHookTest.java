package com.subjex.platform.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.subjex.entity.declare.EntityCatalog;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.platform.app.capability.CapabilityCatalog;
import com.subjex.platform.app.entity.HybridEntityStore;
import com.subjex.platform.app.entity.JdbcHybridEntityStore;
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
import com.subjex.platform.contract.inbox.InboxChannel;
import com.subjex.platform.contract.inbox.InboxEntryView;
import com.subjex.platform.contract.inbox.InboxListQuery;
import com.subjex.platform.contract.inbox.InboxPort;
import com.subjex.platform.contract.inbox.InboxRecipientRealm;
import com.subjex.platform.contract.inbox.InboxSeverity;
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
 * RepairTicketWorkflowInboxHookTest — IM-3: request_approval / approve / reject / close inbox fan-out.
 * 目的：精选报修流转写 WORKFLOW 站内信；空候选人空操作。
 */
@Tag(WorkflowTags.WORKFLOW)
class RepairTicketWorkflowInboxHookTest {

    private static final String TENANT = "tenant-wf-im3";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC);

    private JdbcTemplate jdbc;
    private WorkflowService workflows;
    private InboxPort inbox;
    private HybridEntityStore hybrid;
    private RenderedEntity repairEntity;
    private JdbcOrganizationStore orgs;

    private OperatorPrincipal writer;
    private OperatorPrincipal inScopeApprover;
    private OperatorPrincipal closer;

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
        RepairTicketOrgApproverGate gate = new RepairTicketOrgApproverGate(
                hybrid, catalog, new OrganizationScopeResolver(orgs), orgs);
        inbox = new JdbcInboxPort(jdbc, tx);
        JdbcWorkflowStore store = new JdbcWorkflowStore(jdbc, tx);
        OperatorTenantAccess tenantAccess = new JdbcOperatorTenantAccess(jdbc, tx);
        workflows = new WorkflowService(
                store,
                tenantAccess,
                new CapabilityCatalog(),
                new OperatorActionAudit(new JdbcAuditPort(jdbc), CLOCK),
                CLOCK,
                gate,
                null,
                new RepairTicketWorkflowInboxHook(inbox, gate));
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
                        OperatorPermission.WORKFLOW_APPROVE.permissionName(),
                        OperatorPermission.WORKFLOW_WRITE.permissionName()));
        closer = operator(
                "closer",
                "sub-closer",
                Set.of(
                        OperatorPermission.WORKFLOW_READ.permissionName(),
                        OperatorPermission.WORKFLOW_WRITE.permissionName()));
        grant(writer.subjectId());
        grant(inScopeApprover.subjectId());
        grant(closer.subjectId());
    }

    @Test
    void requestApprovalNotifiesOrgCandidatesWithAction() {
        saveTicket("t-im3-req", "org-team");
        WorkflowInstance started = workflows.start(
                writer, RepairTicketWorkflowDefinitions.REPAIR_TICKET, TENANT, "repair-ticket", "t-im3-req");
        workflows.transition(writer, started.instanceId(), WorkflowAction.START_WORK, null);
        workflows.transition(writer, started.instanceId(), WorkflowAction.REQUEST_APPROVAL, null);

        List<InboxEntryView> mgrTeam = listFor("sub-mgr-team");
        assertThat(mgrTeam).hasSize(1);
        assertThat(mgrTeam.get(0).message().channel()).isEqualTo(InboxChannel.WORKFLOW);
        assertThat(mgrTeam.get(0).message().severity()).isEqualTo(InboxSeverity.ACTION);
        assertThat(mgrTeam.get(0).message().title()).isEqualTo("报修单待审批");
        assertThat(mgrTeam.get(0).message().refKind())
                .isEqualTo(RepairTicketWorkflowInboxHook.REF_KIND_WORKFLOW_INSTANCE);
        assertThat(mgrTeam.get(0).message().refId()).isEqualTo(started.instanceId());
        assertThat(mgrTeam.get(0).message().deepLink()).isEqualTo("/pages/repair-ticket/t-im3-req");

        assertThat(listFor("sub-mgr-eng")).hasSize(1);
        // Actor who requested approval is not notified — 申请人自己不收
        assertThat(listFor("sub-writer")).isEmpty();
    }

    @Test
    void approveNotifiesReporterWithInfo() {
        saveTicket("t-im3-appr", "org-team");
        WorkflowInstance started = workflows.start(
                writer, RepairTicketWorkflowDefinitions.REPAIR_TICKET, TENANT, "repair-ticket", "t-im3-appr");
        workflows.transition(writer, started.instanceId(), WorkflowAction.START_WORK, null);
        workflows.transition(writer, started.instanceId(), WorkflowAction.REQUEST_APPROVAL, null);
        workflows.transition(inScopeApprover, started.instanceId(), WorkflowAction.APPROVE, "ok");

        List<InboxEntryView> reporter = listFor("sub-writer");
        assertThat(reporter).hasSize(1);
        assertThat(reporter.get(0).message().title()).isEqualTo("报修单已批准");
        assertThat(reporter.get(0).message().severity()).isEqualTo(InboxSeverity.INFO);
        assertThat(reporter.get(0).message().channel()).isEqualTo(InboxChannel.WORKFLOW);
        // Approver does not get the decision notice — 审批人不收决策回执
        assertThat(listFor("sub-eng").stream()
                        .filter(v -> "报修单已批准".equals(v.message().title()))
                        .toList())
                .isEmpty();
    }

    @Test
    void rejectNotifiesReporter() {
        saveTicket("t-im3-rej", "org-team");
        WorkflowInstance started = workflows.start(
                writer, RepairTicketWorkflowDefinitions.REPAIR_TICKET, TENANT, "repair-ticket", "t-im3-rej");
        workflows.transition(writer, started.instanceId(), WorkflowAction.START_WORK, null);
        workflows.transition(writer, started.instanceId(), WorkflowAction.REQUEST_APPROVAL, null);
        workflows.transition(inScopeApprover, started.instanceId(), WorkflowAction.REJECT, "fix");

        assertThat(listFor("sub-writer"))
                .anySatisfy(v -> {
                    assertThat(v.message().title()).isEqualTo("报修单已驳回");
                    assertThat(v.message().severity()).isEqualTo(InboxSeverity.INFO);
                });
        assertThat(workflows.status(writer, started.instanceId()).instance().currentState())
                .isEqualTo(WorkflowState.OPEN);
    }

    @Test
    void closeNotifiesReporter() {
        saveTicket("t-im3-close", "org-team");
        WorkflowInstance started = workflows.start(
                writer, RepairTicketWorkflowDefinitions.REPAIR_TICKET, TENANT, "repair-ticket", "t-im3-close");
        workflows.transition(writer, started.instanceId(), WorkflowAction.START_WORK, null);
        workflows.transition(writer, started.instanceId(), WorkflowAction.REQUEST_APPROVAL, null);
        workflows.transition(inScopeApprover, started.instanceId(), WorkflowAction.APPROVE, null);
        // Another operator closes so reporter still receives INFO (self-close is a no-op recipient filter).
        // 由另一操作员关闭，报告人仍收 INFO（自己关单会被收件人过滤掉）。
        workflows.transition(closer, started.instanceId(), WorkflowAction.CLOSE, null);

        assertThat(listFor("sub-writer"))
                .anySatisfy(v -> assertThat(v.message().title()).isEqualTo("报修单已关闭"));
    }

    @Test
    void requestApprovalWithNoOrgIsNoOpInbox() {
        saveTicket("t-im3-noorg", null);
        WorkflowInstance started = workflows.start(
                writer, RepairTicketWorkflowDefinitions.REPAIR_TICKET, TENANT, "repair-ticket", "t-im3-noorg");
        workflows.transition(writer, started.instanceId(), WorkflowAction.START_WORK, null);
        workflows.transition(writer, started.instanceId(), WorkflowAction.REQUEST_APPROVAL, null);

        Integer messages = jdbc.queryForObject("SELECT COUNT(*) FROM inbox_message WHERE tenant_id = ?", Integer.class, TENANT);
        assertThat(messages).isZero();
        assertThat(workflows.status(writer, started.instanceId()).instance().currentState())
                .isEqualTo(WorkflowState.AWAITING_APPROVAL);
    }

    private List<InboxEntryView> listFor(String subjectId) {
        return inbox.listEntries(
                TENANT, InboxRecipientRealm.OPERATOR, subjectId, InboxListQuery.defaults());
    }

    private void seedOrgTree() {
        jdbc.update(
                "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?,?,?)",
                "sub-eng",
                "Eng",
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
        jdbc.update(
                "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?,?,?)",
                "sub-closer",
                "Closer",
                "PERSON");
        orgs.upsertOrganization("org-root", "Root", "ACTIVE");
        orgs.upsertOrganization("org-eng", "Eng", "ACTIVE");
        orgs.upsertOrganization("org-team", "Team", "ACTIVE");
        orgs.upsertTenantOrganization(TENANT, "org-root", "ACTIVE");
        orgs.upsertTenantOrganization(TENANT, "org-eng", "ACTIVE");
        orgs.upsertTenantOrganization(TENANT, "org-team", "ACTIVE");
        orgs.setContainsParent("org-eng", "org-root");
        orgs.setContainsParent("org-team", "org-eng");
        orgs.upsertMembership("sub-eng", "org-eng", "ACTIVE");
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
