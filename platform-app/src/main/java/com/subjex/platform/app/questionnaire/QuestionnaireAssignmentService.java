package com.subjex.platform.app.questionnaire;

import com.subjex.platform.app.organization.JdbcOrganizationStore;
import com.subjex.platform.app.organization.Organization;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.contract.audit.AuditEntry;
import com.subjex.platform.contract.audit.AuditOutcome;
import com.subjex.platform.contract.audit.AuditPort;
import com.subjex.platform.contract.questionnaire.QuestionnaireCollectMode;
import com.subjex.platform.contract.questionnaire.QuestionnaireDefinition;
import com.subjex.platform.contract.questionnaire.QuestionnairePort;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

/**
 * QuestionnaireAssignmentService — operator list/add/remove of assignments (questionnaire.read/write).
 * 操作员列出、添加、移除问卷分配；复用已有 questionnaire.read / questionnaire.write，不新开权限。
 */
public final class QuestionnaireAssignmentService {

    public static final String AUDIT_ADD = "questionnaire.assignment.add";

    public static final String AUDIT_REMOVE = "questionnaire.assignment.remove";

    private final QuestionnairePort questionnaires;
    private final QuestionnaireAssignmentStore assignments;
    private final JdbcOrganizationStore organizations;
    private final JdbcTemplate jdbc;
    private final OperatorTenantAccess tenantAccess;
    private final TenantGuard tenantGuard;
    private final AuditPort audit;
    private final Clock clock;

    public QuestionnaireAssignmentService(
            QuestionnairePort questionnaires,
            QuestionnaireAssignmentStore assignments,
            JdbcOrganizationStore organizations,
            JdbcTemplate jdbc,
            OperatorTenantAccess tenantAccess,
            TenantGuard tenantGuard,
            AuditPort audit,
            Clock clock) {
        this.questionnaires = Objects.requireNonNull(questionnaires, "questionnaires");
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.organizations = Objects.requireNonNull(organizations, "organizations");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.tenantGuard = Objects.requireNonNull(tenantGuard, "tenantGuard");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<QuestionnaireAssignment> list(OperatorPrincipal operator, String tenantId, String questionnaireId) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.QUESTIONNAIRE_READ.permissionName());
        QuestionnaireDefinition definition = requireQuestionnaire(tid, questionnaireId);
        return assignments.list(tid, definition.questionnaireId());
    }

    /**
     * Add an assignment. Idempotent on the same tuple. Only {@code AUTH_END_USER} questionnaires.
     * 添加分配；同一元组幂等。仅认证终端问卷可分配。
     */
    public QuestionnaireAssignment add(
            OperatorPrincipal operator,
            String tenantId,
            String questionnaireId,
            String assigneeKind,
            String assigneeId) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.QUESTIONNAIRE_WRITE.permissionName());
        QuestionnaireDefinition definition = requireQuestionnaire(tid, questionnaireId);
        if (definition.collectMode() != QuestionnaireCollectMode.AUTH_END_USER) {
            throw new IllegalArgumentException("assignments apply only to AUTH_END_USER questionnaires");
        }
        QuestionnaireAssigneeKind kind = QuestionnaireAssigneeKind.require(assigneeKind);
        String id = requireToken(assigneeId, "assigneeId");
        if (kind == QuestionnaireAssigneeKind.SUBJECT) {
            requireActiveIdentityInTenant(tid, id);
        } else {
            requireAssignableOrganization(tid, id);
        }
        return assignments
                .findByAssignee(tid, definition.questionnaireId(), kind, id)
                .orElseGet(() -> {
                    QuestionnaireAssignment created = assignments.insert(new QuestionnaireAssignment(
                            UUID.randomUUID().toString(),
                            tid,
                            definition.questionnaireId(),
                            kind,
                            id,
                            clock.instant(),
                            operator.subjectId()));
                    audit.record(new AuditEntry(
                            UUID.randomUUID().toString(),
                            tid,
                            operator.identityId() == null ? operator.subjectId() : operator.identityId(),
                            AUDIT_ADD,
                            definition.questionnaireId() + ":" + kind.name() + ":" + id,
                            AuditOutcome.ALLOWED,
                            clock.instant()));
                    return created;
                });
    }

    public void remove(OperatorPrincipal operator, String tenantId, String questionnaireId, String assignmentId) {
        String tid = requireTenantAndGrant(operator, tenantId);
        requirePermission(operator, OperatorPermission.QUESTIONNAIRE_WRITE.permissionName());
        QuestionnaireDefinition definition = requireQuestionnaire(tid, questionnaireId);
        if (!assignments.delete(tid, definition.questionnaireId(), requireToken(assignmentId, "assignmentId"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "assignment not found");
        }
        audit.record(new AuditEntry(
                UUID.randomUUID().toString(),
                tid,
                operator.identityId() == null ? operator.subjectId() : operator.identityId(),
                AUDIT_REMOVE,
                definition.questionnaireId() + ":" + assignmentId.trim(),
                AuditOutcome.ALLOWED,
                clock.instant()));
    }

    private QuestionnaireDefinition requireQuestionnaire(String tenantId, String questionnaireId) {
        String qid = requireToken(questionnaireId, "questionnaireId");
        return questionnaires
                .get(tenantId, qid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "questionnaire not found"));
    }

    private void requireActiveIdentityInTenant(String tenantId, String subjectId) {
        Integer n = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                  FROM subject_identity
                 WHERE subject_id = ? AND tenant_id = ? AND UPPER(identity_state) = ?
                """,
                Integer.class,
                subjectId,
                tenantId,
                "ACTIVE");
        if (n == null || n == 0) {
            throw new IllegalArgumentException("assignee is not an active member of this tenant");
        }
    }

    private void requireAssignableOrganization(String tenantId, String organizationId) {
        Organization org = organizations
                .findOrganization(organizationId)
                .orElseThrow(() -> new IllegalArgumentException("organization not found in this tenant"));
        if (!JdbcOrganizationStore.STATE_ACTIVE.equals(org.organizationState())) {
            throw new IllegalArgumentException("organization is not active");
        }
        boolean linked = organizations.listOrganizationsLinkedToTenant(tenantId).stream()
                .anyMatch(linkedOrg -> organizationId.equals(linkedOrg.organizationId()));
        if (!linked) {
            throw new IllegalArgumentException("organization is not linked to this tenant");
        }
    }

    private String requireTenantAndGrant(OperatorPrincipal operator, String tenantId) {
        Objects.requireNonNull(operator, "operator");
        tenantGuard.requireTenant(tenantId);
        String tid = tenantId.trim();
        tenantAccess.requireGranted(operator, tid);
        return tid;
    }

    private static void requirePermission(OperatorPrincipal operator, String permission) {
        if (!operator.permissionNames().contains(permission)) {
            throw new AccessDeniedException("missing permission " + permission);
        }
    }

    private static String requireToken(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
