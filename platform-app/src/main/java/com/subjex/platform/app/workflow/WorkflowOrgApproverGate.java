package com.subjex.platform.app.workflow;

import com.subjex.entity.declare.EntityCatalog;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.platform.app.entity.HybridEntityStore;
import com.subjex.platform.app.organization.JdbcOrganizationStore;
import com.subjex.platform.app.organization.Membership;
import com.subjex.platform.app.organization.OrganizationScopeResolver;
import com.subjex.platform.app.security.OrganizationScope;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * WorkflowOrgApproverGate — declaration-driven org-scoped approval (ADR 0016 / WP4-2).
 * 声明驱动的组织范围审批。
 * <p>
 * <strong>Rule / 规则：</strong>
 * <ul>
 *   <li>Applied only when the machine's declaration has {@code orgScope} (repair-ticket YAML
 *       already does). Other machines opt in the same way. Not applied otherwise.</li>
 *   <li><strong>Enforce</strong> on declared {@code onActions}: actor's
 *       {@link OrganizationScopeResolver#resolveSelfAndDescendants} must
 *       {@link OrganizationScope#contains contain} the org id on the bound record (membership
 *       roots + ACTIVE CONTAINS). Missing org on the record → fail-closed.</li>
 *   <li><strong>Candidates</strong> (console advisory): ACTIVE {@code organization_manager} of the
 *       record org and each ACTIVE CONTAINS ancestor, plus ACTIVE memberships on that org
 *       itself.</li>
 * </ul>
 * Dual-approve and {@code workflow.approve} stay in {@link WorkflowService}. Relationship is not
 * authorization. Semantics match the former repair-ticket-only gate (WF-RT-3).
 * 仅在声明了 orgScope 时生效；缺组织失败关闭；关系不是授权。语义与原报修单门闩一致。
 */
public final class WorkflowOrgApproverGate {

    /** Default organization field when a declaration omits a custom name — 声明未另写字段名时的默认组织字段。 */
    public static final String ORGANIZATION_FIELD = "organization";

    private final HybridEntityStore hybridEntities;
    private final EntityCatalog entityCatalog;
    private final OrganizationScopeResolver scopeResolver;
    private final JdbcOrganizationStore orgStore;
    private final WorkflowCatalog workflows;

    public WorkflowOrgApproverGate(
            HybridEntityStore hybridEntities,
            EntityCatalog entityCatalog,
            OrganizationScopeResolver scopeResolver,
            JdbcOrganizationStore orgStore,
            WorkflowCatalog workflows) {
        this.hybridEntities = Objects.requireNonNull(hybridEntities, "hybridEntities");
        this.entityCatalog = Objects.requireNonNull(entityCatalog, "entityCatalog");
        this.scopeResolver = Objects.requireNonNull(scopeResolver, "scopeResolver");
        this.orgStore = Objects.requireNonNull(orgStore, "orgStore");
        this.workflows = Objects.requireNonNull(workflows, "workflows");
    }

    /**
     * Fail-closed if the actor cannot approve this org-scoped instance —
     * 审批人不在范围内则失败关闭。
     */
    public void requireApproverInScope(WorkflowInstance instance, String actorSubjectId) {
        Objects.requireNonNull(instance, "instance");
        String actor = requireNonBlank(actorSubjectId, "actorSubjectId");
        Optional<WorkflowOrgScopeDeclaration> orgScope = orgScopeOf(instance);
        if (orgScope.isEmpty()) {
            return;
        }
        String orgId = requireRecordOrganization(instance, orgScope.get());
        OrganizationScope scope = scopeResolver.resolveSelfAndDescendants(instance.tenantId(), actor);
        if (!scope.contains(orgId)) {
            throw new WorkflowApproverOutOfScope(
                    "workflow approve/reject requires org scope covering ticket organization "
                            + orgId
                            + " (actor "
                            + actor
                            + ")");
        }
    }

    /**
     * Advisory candidate subject ids for console — 控制台参考用候选主体 id。
     */
    public List<String> listCandidateSubjectIds(WorkflowInstance instance) {
        Objects.requireNonNull(instance, "instance");
        Optional<WorkflowOrgScopeDeclaration> orgScope = orgScopeOf(instance);
        if (orgScope.isEmpty()) {
            return List.of();
        }
        Optional<String> org = readRecordOrganization(instance, orgScope.get());
        if (org.isEmpty()) {
            return List.of();
        }
        String ticketOrg = org.get();
        Set<String> candidates = new LinkedHashSet<>();
        for (String oid : orgChainSelfAndAncestors(ticketOrg)) {
            orgStore.findActiveManagerSubjectId(oid).ifPresent(candidates::add);
        }
        for (Membership m : orgStore.listMembershipsForOrganization(ticketOrg)) {
            if (JdbcOrganizationStore.STATE_ACTIVE.equals(m.membershipState())) {
                candidates.add(m.subjectId());
            }
        }
        return List.copyOf(candidates);
    }

    public Optional<String> readRecordOrganization(WorkflowInstance instance, WorkflowOrgScopeDeclaration orgScope) {
        String entityKey = instance.entityKey();
        RenderedEntity entity = entityCatalog
                .find(entityKey)
                .orElseThrow(() -> new IllegalStateException("entity declaration missing for workflow org scope: "
                        + entityKey));
        Optional<Map<String, Object>> row =
                hybridEntities.findById(entity, instance.recordId(), instance.tenantId());
        if (row.isEmpty()) {
            return Optional.empty();
        }
        String field = orgScope.field() == null || orgScope.field().isBlank()
                ? ORGANIZATION_FIELD
                : orgScope.field();
        Object raw = row.get().get(field);
        if (raw == null || raw.toString().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(raw.toString().trim());
    }

    private Optional<WorkflowOrgScopeDeclaration> orgScopeOf(WorkflowInstance instance) {
        return workflows.find(instance.definitionKey()).flatMap(WorkflowDefinition::orgScope);
    }

    private String requireRecordOrganization(WorkflowInstance instance, WorkflowOrgScopeDeclaration orgScope) {
        return readRecordOrganization(instance, orgScope)
                .orElseThrow(() -> new WorkflowApproverOutOfScope(
                        "workflow record "
                                + instance.recordId()
                                + " has no organization; cannot approve without org scope"));
    }

    /** Record org + ACTIVE CONTAINS ancestors (rootward) — 记录组织及 CONTAINS 祖先。 */
    private List<String> orgChainSelfAndAncestors(String organizationId) {
        List<String> chain = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String current = organizationId;
        while (current != null && seen.add(current)) {
            chain.add(current);
            current = orgStore.findActiveContainsParent(current).orElse(null);
        }
        return chain;
    }

    private static String requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is missing");
        }
        return value.trim();
    }
}
