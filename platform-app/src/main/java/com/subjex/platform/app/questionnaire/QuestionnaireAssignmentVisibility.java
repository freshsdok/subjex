package com.subjex.platform.app.questionnaire;

import com.subjex.platform.app.organization.JdbcOrganizationStore;
import com.subjex.platform.app.organization.Membership;
import com.subjex.platform.app.organization.Organization;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * QuestionnaireAssignmentVisibility — whether an end-user subject may see or fill a questionnaire.
 * 判断终端主体能否看见或填写问卷。
 * <p>
 * A published {@code AUTH_END_USER} questionnaire is visible only when assigned to the
 * subject, or to an organization the subject belongs to. Organization membership follows
 * the existing ontology: {@code TenantOrganization} must be ACTIVE, membership must be
 * ACTIVE (SUSPENDED / DISABLED / ENDED do not count), and the organization itself must
 * be ACTIVE. An assignment to a department also includes ACTIVE CONTAINS descendants,
 * so members of a sub-department can see a questionnaire assigned to the parent.
 * Being a member of a parent only does not grant a child-department assignment.
 * 已发布的认证问卷仅在分配给该主体，或分配给其所属部门时可见。部门成员关系沿用已有本体：
 * 租户关联、成员关系与组织状态均须为 ACTIVE；分配给上级部门时，下级部门的在职成员也被包含；
 * 仅属于上级并不获得下级部门的分配。
 */
public final class QuestionnaireAssignmentVisibility {

    private final QuestionnaireAssignmentStore assignments;
    private final JdbcOrganizationStore organizations;

    public QuestionnaireAssignmentVisibility(
            QuestionnaireAssignmentStore assignments, JdbcOrganizationStore organizations) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.organizations = Objects.requireNonNull(organizations, "organizations");
    }

    public boolean isAssigned(String tenantId, String questionnaireId, String subjectId) {
        if (blank(tenantId) || blank(questionnaireId) || blank(subjectId)) {
            return false;
        }
        List<QuestionnaireAssignment> rows = assignments.list(tenantId, questionnaireId);
        if (rows.isEmpty()) {
            return false;
        }
        return matches(tenantId, subjectId, rows);
    }

    /**
     * True when any of the given assignments cover this subject in the tenant.
     * 给定分配中是否有一条覆盖该主体。
     */
    public boolean matches(String tenantId, String subjectId, List<QuestionnaireAssignment> rows) {
        if (rows == null || rows.isEmpty() || blank(tenantId) || blank(subjectId)) {
            return false;
        }
        String sid = subjectId.trim();
        for (QuestionnaireAssignment row : rows) {
            if (row.assigneeKind() == QuestionnaireAssigneeKind.SUBJECT && sid.equals(row.assigneeId())) {
                return true;
            }
        }
        Set<String> membershipOrgs = activeMembershipOrganizationIds(tenantId, sid);
        if (membershipOrgs.isEmpty()) {
            return false;
        }
        for (QuestionnaireAssignment row : rows) {
            if (row.assigneeKind() != QuestionnaireAssigneeKind.ORGANIZATION) {
                continue;
            }
            if (!organizationAssignable(tenantId, row.assigneeId())) {
                continue;
            }
            Set<String> covered = organizations.containsSelfAndDescendants(row.assigneeId());
            for (String orgId : membershipOrgs) {
                if (covered.contains(orgId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * ACTIVE membership orgs of the subject that are ACTIVE and ACTIVE-linked to the tenant.
     * 该主体在本租户内 ACTIVE 关联、组织本身也为 ACTIVE 的在职部门。
     */
    Set<String> activeMembershipOrganizationIds(String tenantId, String subjectId) {
        Set<String> ids = new HashSet<>();
        for (Membership membership : organizations.listMembershipsForTenant(tenantId, subjectId)) {
            if (!JdbcOrganizationStore.STATE_ACTIVE.equals(membership.membershipState())) {
                continue;
            }
            Organization org = organizations.findOrganization(membership.organizationId()).orElse(null);
            if (org != null && JdbcOrganizationStore.STATE_ACTIVE.equals(org.organizationState())) {
                ids.add(org.organizationId());
            }
        }
        return ids;
    }

    /**
     * Whether an organization may still grant visibility in this tenant (ACTIVE + ACTIVE link).
     * 该组织当前是否仍能在本租户授予可见性（组织 ACTIVE 且租户关联 ACTIVE）。
     */
    boolean organizationAssignable(String tenantId, String organizationId) {
        if (blank(tenantId) || blank(organizationId)) {
            return false;
        }
        Organization org = organizations.findOrganization(organizationId).orElse(null);
        if (org == null || !JdbcOrganizationStore.STATE_ACTIVE.equals(org.organizationState())) {
            return false;
        }
        return organizations.listOrganizationsLinkedToTenant(tenantId).stream()
                .anyMatch(linked -> organizationId.equals(linked.organizationId())
                        && JdbcOrganizationStore.STATE_ACTIVE.equals(linked.organizationState()));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
