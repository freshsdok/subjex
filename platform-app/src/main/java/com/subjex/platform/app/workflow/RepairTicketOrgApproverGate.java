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
 * RepairTicketOrgApproverGate — WF-RT-3 repair-ticket-only org-scoped approval (not a generic DSL).
 * <p>
 * <strong>Rule / 规则：</strong>
 * <ul>
 *   <li>Read hybrid field {@code organization} on the bound repair-ticket record.</li>
 *   <li><strong>Enforce</strong> on approve/reject: actor's
 *       {@link OrganizationScopeResolver#resolveSelfAndDescendants} must
 *       {@link OrganizationScope#contains contain} that org id (membership roots + ACTIVE CONTAINS).
 *       Missing org on the ticket → fail-closed.</li>
 *   <li><strong>Candidates</strong> (console advisory): ACTIVE {@code organization_manager} of the
 *       ticket org and each ACTIVE CONTAINS ancestor, plus ACTIVE memberships on the ticket org
 *       itself.</li>
 * </ul>
 * Dual-approve (requester ≠ consumer) and {@code workflow.approve} stay elsewhere.
 * 仅报修单；强制用审批人 OrgScope 覆盖工单组织；候选人=本组织及祖先主管 + 本组织成员。
 */
public final class RepairTicketOrgApproverGate {

    public static final String ORGANIZATION_FIELD = "organization";

    private final HybridEntityStore hybridEntities;
    private final EntityCatalog entityCatalog;
    private final OrganizationScopeResolver scopeResolver;
    private final JdbcOrganizationStore orgStore;

    public RepairTicketOrgApproverGate(
            HybridEntityStore hybridEntities,
            EntityCatalog entityCatalog,
            OrganizationScopeResolver scopeResolver,
            JdbcOrganizationStore orgStore) {
        this.hybridEntities = Objects.requireNonNull(hybridEntities, "hybridEntities");
        this.entityCatalog = Objects.requireNonNull(entityCatalog, "entityCatalog");
        this.scopeResolver = Objects.requireNonNull(scopeResolver, "scopeResolver");
        this.orgStore = Objects.requireNonNull(orgStore, "orgStore");
    }

    /**
     * Fail-closed if actor cannot approve this repair-ticket instance —
     * 审批人不在范围内则失败关闭。
     */
    public void requireApproverInScope(WorkflowInstance instance, String actorSubjectId) {
        Objects.requireNonNull(instance, "instance");
        String actor = requireNonBlank(actorSubjectId, "actorSubjectId");
        if (!RepairTicketWorkflowDefinitions.REPAIR_TICKET.equals(instance.definitionKey())
                && !RepairTicketWorkflowDefinitions.REPAIR_TICKET.equals(instance.entityKey())) {
            return;
        }
        String orgId = requireTicketOrganization(instance);
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
        if (!RepairTicketWorkflowDefinitions.REPAIR_TICKET.equals(instance.definitionKey())
                && !RepairTicketWorkflowDefinitions.REPAIR_TICKET.equals(instance.entityKey())) {
            return List.of();
        }
        Optional<String> org = readTicketOrganization(instance);
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

    public Optional<String> readTicketOrganization(WorkflowInstance instance) {
        RenderedEntity entity = entityCatalog
                .find(RepairTicketWorkflowDefinitions.REPAIR_TICKET)
                .orElseThrow(() -> new IllegalStateException("classpath repair-ticket entity missing"));
        Optional<Map<String, Object>> row =
                hybridEntities.findById(entity, instance.recordId(), instance.tenantId());
        if (row.isEmpty()) {
            return Optional.empty();
        }
        Object raw = row.get().get(ORGANIZATION_FIELD);
        if (raw == null || raw.toString().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(raw.toString().trim());
    }

    private String requireTicketOrganization(WorkflowInstance instance) {
        return readTicketOrganization(instance)
                .orElseThrow(() -> new WorkflowApproverOutOfScope(
                        "repair-ticket record "
                                + instance.recordId()
                                + " has no organization; cannot approve without org scope"));
    }

    /** Ticket org + ACTIVE CONTAINS ancestors (rootward) — 工单组织及 CONTAINS 祖先。 */
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
