package com.subjex.platform.app.organization;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.security.AccessAction;
import com.subjex.platform.app.security.AccessDecision;
import com.subjex.platform.app.security.AccessDecisionDeniedException;
import com.subjex.platform.app.security.AccessResource;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.OrganizationScope;
import com.subjex.platform.app.security.PolicyContext;
import com.subjex.platform.app.security.PolicyEngine;
import com.subjex.platform.app.security.PolicyPrincipal;
import com.subjex.platform.app.security.PolicyResource;
import com.subjex.platform.contract.audit.AuditOutcome;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * OrganizationApiEndpoint — O5 Organization ontology JSON API.
 * <p>
 * Paths under {@code /api/v1/organizations}. Needs {@code org.read} / {@code org.write}
 * (same as legacy {@code /api/v1/org/**}). Writes hit ontology tables only (O8-4: no map /
 * {@link OrganizationOntologyBackfill} on the happy path). Scope from
 * {@link OrganizationScopeResolver} (Membership + CONTAINS; no map).
 * Out-of-scope reads/writes -> {@link AccessDecision#DENY_ORG_OUT_OF_SCOPE} (fail-closed);
 * null actor -> {@link OrganizationScope#none()}, never silent UNRESTRICTED.
 * <p>
 * O5/O8-4 组织本体 JSON。写只落本体表；范围经 OrganizationScopeResolver（不经 map）。
 * 越范围读/写失败关闭拒绝；无主体 -> NONE，绝不静默 UNRESTRICTED。
 */
@RestController
public class OrganizationApiEndpoint {

    public static final String PATH = JsonApi.BASE + "/organizations";

    private final JdbcOrganizationStore store;
    private final OrganizationScopeResolver scopeResolver;
    private final OperatorTenantAccess tenantAccess;
    private final OperatorActionAudit audit;
    private final PolicyEngine policyEngine;

    public OrganizationApiEndpoint(
            JdbcOrganizationStore store,
            OrganizationScopeResolver scopeResolver,
            OperatorTenantAccess tenantAccess,
            OperatorActionAudit audit,
            PolicyEngine policyEngine) {
        this.store = Objects.requireNonNull(store, "store");
        this.scopeResolver = Objects.requireNonNull(scopeResolver, "scopeResolver");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine");
    }

    /** List tenant-linked organizations filtered by scope — 列出租户关联组织并按范围过滤。 */
    @GetMapping(PATH)
    public OrganizationsDocument list(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        String tid = requireTenantIdParam(tenantId);
        OrganizationScope scope = resolveScope(operator, tid);
        List<OrganizationDocument> organizations = store.listOrganizationsLinkedToTenant(tid).stream()
                .filter(o -> inScopeOrUnspecified(scope, o.organizationId()))
                .map(o -> toDocument(tid, o))
                .toList();
        return new OrganizationsDocument(organizations);
    }

    /** Get one; out of scope -> DENY_ORG_OUT_OF_SCOPE — 读单个；越范围失败关闭。 */
    @GetMapping(PATH + "/{organizationId}")
    public OrganizationDocument getOne(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("organizationId") String organizationId,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        String tid = requireTenantIdParam(tenantId);
        OrganizationScope scope = resolveScope(operator, tid);
        Organization org = store.listOrganizationsLinkedToTenant(tid).stream()
                .filter(o -> o.organizationId().equals(organizationId.trim()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!inScopeOrUnspecified(scope, org.organizationId())) {
            AccessDecision decision = AccessDecision.deny(
                    operator == null ? null : operator.subjectId(),
                    tid,
                    scope,
                    AccessResource.of("organization", org.organizationId()),
                    AccessAction.of("read"),
                    OperatorPermission.ORG_READ.permissionName(),
                    AccessDecision.DENY_ORG_OUT_OF_SCOPE);
            throw new AccessDecisionDeniedException(decision, OperatorPermission.ORG_READ.permissionName());
        }
        return toDocument(tid, org);
    }

    /** Upsert organization; write scope fail-closed — 写入组织；写范围失败关闭。 */
    @PutMapping(PATH + "/{organizationId}")
    public OrganizationDocument upsert(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("organizationId") String organizationId,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody OrganizationWriteRequest body) {
        String tid = requireTenant(operator, tenantId);
        if (body == null) {
            throw new IllegalArgumentException("body required");
        }
        String oid = requireNonBlank(organizationId, "organizationId");
        OrganizationScope scope = resolveScope(operator, tid);
        requireOrganizationWritable(operator, tid, scope, oid, body.parentOrganizationId());
        Organization saved = store.upsertOrganization(oid, body.organizationName(), body.organizationState());
        store.upsertTenantOrganization(tid, oid, JdbcOrganizationStore.STATE_ACTIVE);
        store.setContainsParent(oid, body.parentOrganizationId());
        audit.record(operator, "organization.upsert", tid + "/" + saved.organizationId(), AuditOutcome.ALLOWED);
        return toDocument(tid, saved);
    }

    /** List memberships in scope — 列出范围内成员关系。 */
    @GetMapping(PATH + "/memberships")
    public MembershipsDocument memberships(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "subjectId", required = false) String subjectId) {
        String tid = requireTenantIdParam(tenantId);
        OrganizationScope scope = resolveScope(operator, tid);
        List<MembershipDocument> memberships = store.listMembershipsForTenant(tid, subjectId).stream()
                .filter(m -> inScopeOrUnspecified(scope, m.organizationId()))
                .map(m -> membershipDocument(tid, m))
                .toList();
        return new MembershipsDocument(memberships);
    }

    /** Upsert membership; org must be in scope + linked to tenant — 写入成员；组织须在范围内且已挂租户。 */
    @PutMapping(PATH + "/memberships")
    public MembershipDocument upsertMembership(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody MembershipWriteRequest body) {
        String tid = requireTenant(operator, tenantId);
        if (body == null) {
            throw new IllegalArgumentException("body required");
        }
        OrganizationScope scope = resolveScope(operator, tid);
        requireMembershipOrganizationInScope(operator, tid, scope, body.organizationId());
        if (!store.organizationExists(body.organizationId())) {
            throw new IllegalArgumentException("organization not found");
        }
        // Organization must be linked to this tenant for tenant-scoped console writes.
        boolean linked = store.listOrganizationsLinkedToTenant(tid).stream()
                .anyMatch(o -> o.organizationId().equals(body.organizationId().trim()));
        if (!linked) {
            throw new IllegalArgumentException("organization not linked to tenant");
        }
        Membership saved =
                store.upsertMembership(body.subjectId(), body.organizationId(), body.membershipState());
        audit.record(
                operator,
                "organization.membership.upsert",
                tid + "/" + saved.subjectId() + "/" + saved.organizationId(),
                AuditOutcome.ALLOWED);
        return membershipDocument(tid, saved);
    }

    /** End membership; out of scope deny — 结束成员；越范围拒绝。 */
    @DeleteMapping(PATH + "/memberships")
    public void removeMembership(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "subjectId", required = false) String subjectId,
            @RequestParam(value = "organizationId", required = false) String organizationId) {
        String tid = requireTenant(operator, tenantId);
        OrganizationScope scope = resolveScope(operator, tid);
        requireMembershipOrganizationInScope(operator, tid, scope, organizationId);
        if (!store.endMembership(subjectId, organizationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        audit.record(
                operator,
                "organization.membership.remove",
                tid + "/" + subjectId.trim() + "/" + organizationId.trim(),
                AuditOutcome.ALLOWED);
    }




    /**
     * Reorder ACTIVE CONTAINS children under parent (Should S2).
     * 重排父节点下 ACTIVE CONTAINS 子顺序。
     */
    @PostMapping(PATH + "/{organizationId}/children/order")
    public OrganizationsDocument reorderChildren(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("organizationId") String organizationId,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody ChildrenOrderRequest body) {
        String tid = requireTenant(operator, tenantId);
        String parent = requireNonBlank(organizationId, "organizationId");
        requireLinkedToTenant(tid, parent);
        if (body == null || body.orderedChildIds() == null) {
            throw new IllegalArgumentException("orderedChildIds required");
        }
        OrganizationScope scope = resolveScope(operator, tid);
        requireMoveWritable(operator, tid, scope, parent, null);
        for (String child : body.orderedChildIds()) {
            requireLinkedToTenant(tid, child);
            requireMoveWritable(operator, tid, scope, child, parent);
        }
        store.reorderContainsChildren(parent, body.orderedChildIds());
        audit.record(
                operator,
                "org.organization.children_order",
                tid + "/" + parent + "/" + body.orderedChildIds().size(),
                AuditOutcome.ALLOWED);
        List<OrganizationDocument> organizations = store.listOrganizationsLinkedToTenant(tid).stream()
                .filter(o -> inScopeOrUnspecified(scope, o.organizationId()))
                .map(o -> toDocument(tid, o))
                .toList();
        return new OrganizationsDocument(organizations);
    }

    /**
     * Preview multi-select CONTAINS reparent (Should S1) — no write.
     * 多选改挂载预览；不落库。
     */
    @PostMapping(PATH + "/moves/preview")
    public BatchMovePreviewDocument previewBatchMove(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody BatchMoveRequest body) {
        String tid = requireTenant(operator, tenantId);
        if (body == null || body.organizationIds() == null || body.organizationIds().isEmpty()) {
            throw new IllegalArgumentException("organizationIds required");
        }
        String newParent = normalizeParent(body.newParentOrganizationId());
        if (newParent != null) {
            requireLinkedToTenant(tid, newParent);
        }
        OrganizationScope scope = resolveScope(operator, tid);
        List<String> ids = dedupePreserve(body.organizationIds());
        // Drop ids that are descendants of another selected id (moving ancestor moves them).
        ids = dropCoveredDescendants(ids);
        List<MovePreviewDocument> items = new ArrayList<>();
        boolean anyCycle = false;
        boolean allInScope = true;
        int totalSubtree = 0;
        int totalMembers = 0;
        for (String oid : ids) {
            requireLinkedToTenant(tid, oid);
            String fromParent = store.findActiveContainsParent(oid).orElse(null);
            boolean wouldCycle = false;
            if (newParent != null) {
                if (newParent.equals(oid) || ids.contains(newParent)) {
                    wouldCycle = true;
                } else {
                    wouldCycle = store.wouldCreateContainsCycle(newParent, oid);
                }
            }
            boolean inScope = isMoveInScope(scope, oid, newParent);
            int subtree = store.containsSelfAndDescendants(oid).size();
            int members = store.countDirectMemberships(oid);
            anyCycle = anyCycle || wouldCycle;
            allInScope = allInScope && inScope;
            totalSubtree += subtree;
            totalMembers += members;
            items.add(new MovePreviewDocument(
                    oid, fromParent, newParent, subtree, members, wouldCycle, inScope));
        }
        return new BatchMovePreviewDocument(
                items, newParent, anyCycle, allInScope, totalSubtree, totalMembers);
    }

    /**
     * Apply multi-select CONTAINS reparent (Should S1) — ordered; fail closed on any cycle/scope.
     * 多选改挂载；任一项成环/越权则拒绝；按序执行。
     */
    @PostMapping(PATH + "/moves")
    public BatchMoveResultDocument batchMove(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody BatchMoveRequest body) {
        String tid = requireTenant(operator, tenantId);
        if (body == null || body.organizationIds() == null || body.organizationIds().isEmpty()) {
            throw new IllegalArgumentException("organizationIds required");
        }
        String newParent = normalizeParent(body.newParentOrganizationId());
        if (newParent != null) {
            requireLinkedToTenant(tid, newParent);
        }
        OrganizationScope scope = resolveScope(operator, tid);
        List<String> ids = dropCoveredDescendants(dedupePreserve(body.organizationIds()));
        for (String oid : ids) {
            requireLinkedToTenant(tid, oid);
            requireMoveWritable(operator, tid, scope, oid, newParent);
            if (newParent != null && (newParent.equals(oid) || ids.contains(newParent))) {
                throw new IllegalArgumentException("CONTAINS relation would form a cycle");
            }
            if (newParent != null && store.wouldCreateContainsCycle(newParent, oid)) {
                throw new IllegalArgumentException("CONTAINS relation would form a cycle");
            }
        }
        List<OrganizationDocument> moved = new ArrayList<>();
        for (String oid : ids) {
            String fromParent = store.findActiveContainsParent(oid).orElse(null);
            store.setContainsParent(oid, newParent);
            Organization org = store.listOrganizationsLinkedToTenant(tid).stream()
                    .filter(o -> o.organizationId().equals(oid))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            String target = fromParent == null ? "-" : fromParent;
            String dest = newParent == null ? "-" : newParent;
            audit.record(
                    operator,
                    "org.organization.move",
                    tid + "/" + oid + ":" + target + "->" + dest,
                    AuditOutcome.ALLOWED);
            moved.add(toDocument(tid, org));
        }
        audit.record(
                operator,
                "org.organization.batch_move",
                tid + "/" + moved.size() + "->" + (newParent == null ? "-" : newParent),
                AuditOutcome.ALLOWED);
        return new BatchMoveResultDocument(moved);
    }

    /**
     * Preview CONTAINS reparent (ORG-UI-2) — no write.
     * 预览改挂载；不落库。
     */
    @PostMapping(PATH + "/{organizationId}/move/preview")
    public MovePreviewDocument previewMove(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("organizationId") String organizationId,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody(required = false) MoveRequest body) {
        String tid = requireTenant(operator, tenantId);
        String oid = requireNonBlank(organizationId, "organizationId");
        String newParent = normalizeParent(body == null ? null : body.newParentOrganizationId());
        requireLinkedToTenant(tid, oid);
        if (newParent != null) {
            requireLinkedToTenant(tid, newParent);
        }
        OrganizationScope scope = resolveScope(operator, tid);
        String fromParent = store.findActiveContainsParent(oid).orElse(null);
        boolean wouldCycle = false;
        if (newParent != null) {
            if (newParent.equals(oid)) {
                wouldCycle = true;
            } else {
                wouldCycle = store.wouldCreateContainsCycle(newParent, oid);
            }
        }
        boolean inScope = isMoveInScope(scope, oid, newParent);
        int subtree = store.containsSelfAndDescendants(oid).size();
        int members = store.countDirectMemberships(oid);
        return new MovePreviewDocument(
                oid, fromParent, newParent, subtree, members, wouldCycle, inScope);
    }

    /**
     * Apply CONTAINS reparent (ORG-UI-2) — cycle reject, OrgScope, audit.
     * 执行改挂载；成环拒绝；范围与审计。
     */
    @PostMapping(PATH + "/{organizationId}/move")
    public OrganizationDocument move(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("organizationId") String organizationId,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody(required = false) MoveRequest body) {
        String tid = requireTenant(operator, tenantId);
        String oid = requireNonBlank(organizationId, "organizationId");
        String newParent = normalizeParent(body == null ? null : body.newParentOrganizationId());
        requireLinkedToTenant(tid, oid);
        if (newParent != null) {
            requireLinkedToTenant(tid, newParent);
        }
        OrganizationScope scope = resolveScope(operator, tid);
        requireMoveWritable(operator, tid, scope, oid, newParent);
        if (newParent != null && newParent.equals(oid)) {
            throw new IllegalArgumentException("organization relation cannot self-reference");
        }
        if (newParent != null && store.wouldCreateContainsCycle(newParent, oid)) {
            throw new IllegalArgumentException("CONTAINS relation would form a cycle");
        }
        String fromParent = store.findActiveContainsParent(oid).orElse(null);
        store.setContainsParent(oid, newParent);
        Organization org = store.listOrganizationsLinkedToTenant(tid).stream()
                .filter(o -> o.organizationId().equals(oid))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String target = fromParent == null ? "-" : fromParent;
        String dest = newParent == null ? "-" : newParent;
        audit.record(
                operator,
                "org.organization.move",
                tid + "/" + oid + ":" + target + "->" + dest,
                AuditOutcome.ALLOWED);
        return toDocument(tid, org);
    }

    /**
     * Preview merge source into target (Should S5) — no write.
     * 预览合并；不落库。
     */
    @PostMapping(PATH + "/{organizationId}/merge/preview")
    public MergePreviewDocument previewMerge(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("organizationId") String organizationId,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody(required = false) MergeRequest body) {
        String tid = requireTenant(operator, tenantId);
        String source = requireNonBlank(organizationId, "organizationId");
        if (body == null || body.targetOrganizationId() == null || body.targetOrganizationId().isBlank()) {
            throw new IllegalArgumentException("targetOrganizationId required");
        }
        String target = body.targetOrganizationId().trim();
        requireLinkedToTenant(tid, source);
        requireLinkedToTenant(tid, target);
        OrganizationScope scope = resolveScope(operator, tid);
        boolean wouldCycle = source.equals(target) || store.containsSelfAndDescendants(source).contains(target);
        boolean inScope = isMoveInScope(scope, source, target)
                && store.listActiveContainsChildren(source).stream().allMatch(scope::contains);
        int members = store.countDirectMemberships(source);
        int children = store.listActiveContainsChildren(source).size();
        int subtree = store.containsSelfAndDescendants(source).size();
        return new MergePreviewDocument(source, target, members, children, subtree, wouldCycle, inScope);
    }

    /**
     * Merge source into target (Should S5) — memberships + children + end source.
     * 合并源到目标；成员与子组织迁移；源 ENDED。
     */
    @PostMapping(PATH + "/{organizationId}/merge")
    public MergeResultDocument merge(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("organizationId") String organizationId,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody(required = false) MergeRequest body) {
        String tid = requireTenant(operator, tenantId);
        String source = requireNonBlank(organizationId, "organizationId");
        if (body == null || body.targetOrganizationId() == null || body.targetOrganizationId().isBlank()) {
            throw new IllegalArgumentException("targetOrganizationId required");
        }
        String target = body.targetOrganizationId().trim();
        requireLinkedToTenant(tid, source);
        requireLinkedToTenant(tid, target);
        OrganizationScope scope = resolveScope(operator, tid);
        requireMoveWritable(operator, tid, scope, source, target);
        for (String child : store.listActiveContainsChildren(source)) {
            requireMoveWritable(operator, tid, scope, child, target);
        }
        if (source.equals(target) || store.containsSelfAndDescendants(source).contains(target)) {
            throw new IllegalArgumentException("cannot merge into a descendant (would cycle)");
        }
        JdbcOrganizationStore.MergeResult result = store.mergeOrganizations(source, target);
        audit.record(
                operator,
                "org.organization.merge",
                tid + "/" + source + "->" + target,
                AuditOutcome.ALLOWED);
        Organization targetOrg = store.listOrganizationsLinkedToTenant(tid).stream()
                .filter(o -> o.organizationId().equals(target))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new MergeResultDocument(
                toDocument(tid, result.endedSource()),
                toDocument(tid, targetOrg),
                result.movedMembershipCount(),
                result.reparentedChildCount());
    }

    /**
     * Preview split (Should S5) — no write.
     * 预览拆分；不落库。
     */
    @PostMapping(PATH + "/{organizationId}/split/preview")
    public SplitPreviewDocument previewSplit(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("organizationId") String organizationId,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody(required = false) SplitRequest body) {
        String tid = requireTenant(operator, tenantId);
        String source = requireNonBlank(organizationId, "organizationId");
        if (body == null) {
            throw new IllegalArgumentException("body required");
        }
        String nid = requireNonBlank(body.newOrganizationId(), "newOrganizationId");
        requireLinkedToTenant(tid, source);
        OrganizationScope scope = resolveScope(operator, tid);
        List<String> children = body.childOrganizationIds() == null ? List.of() : body.childOrganizationIds();
        List<String> members = body.memberSubjectIds() == null ? List.of() : body.memberSubjectIds();
        String newParent = normalizeParent(body.newParentOrganizationId());
        if (newParent == null) {
            newParent = store.findActiveContainsParent(source).orElse(null);
        }
        if (newParent != null) {
            requireLinkedToTenant(tid, newParent);
        }
        boolean inScope = inScopeOrUnspecified(scope, source)
                && (newParent == null || inScopeOrUnspecified(scope, newParent))
                && children.stream().allMatch(c -> inScopeOrUnspecified(scope, c.trim()));
        boolean childrenOk = true;
        HashSet<String> direct = new HashSet<>(store.listActiveContainsChildren(source));
        for (String c : children) {
            if (c == null || c.isBlank() || !direct.contains(c.trim())) {
                childrenOk = false;
                break;
            }
        }
        return new SplitPreviewDocument(
                source,
                nid,
                newParent,
                children.size(),
                members.size(),
                childrenOk,
                inScope);
    }

    /**
     * Split children/members into a new organization (Should S5).
     * 拆分子组织/成员到新组织。
     */
    @PostMapping(PATH + "/{organizationId}/split")
    public SplitResultDocument split(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("organizationId") String organizationId,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody(required = false) SplitRequest body) {
        String tid = requireTenant(operator, tenantId);
        String source = requireNonBlank(organizationId, "organizationId");
        if (body == null) {
            throw new IllegalArgumentException("body required");
        }
        String nid = requireNonBlank(body.newOrganizationId(), "newOrganizationId");
        String nname = requireNonBlank(body.newOrganizationName(), "newOrganizationName");
        requireLinkedToTenant(tid, source);
        OrganizationScope scope = resolveScope(operator, tid);
        List<String> children = body.childOrganizationIds() == null ? List.of() : body.childOrganizationIds();
        List<String> members = body.memberSubjectIds() == null ? List.of() : body.memberSubjectIds();
        String newParent = normalizeParent(body.newParentOrganizationId());
        if (newParent == null) {
            newParent = store.findActiveContainsParent(source).orElse(null);
        }
        if (newParent != null) {
            requireLinkedToTenant(tid, newParent);
        }
        requireOrganizationWritable(operator, tid, scope, nid, newParent);
        requireMoveWritable(operator, tid, scope, source, newParent);
        for (String child : children) {
            requireMoveWritable(operator, tid, scope, child.trim(), nid);
        }
        JdbcOrganizationStore.SplitResult result =
                store.splitOrganization(source, nid, nname, newParent, children, members);
        store.upsertTenantOrganization(tid, nid, JdbcOrganizationStore.STATE_ACTIVE);
        audit.record(
                operator,
                "org.organization.split",
                tid + "/" + source + "->" + nid,
                AuditOutcome.ALLOWED);
        return new SplitResultDocument(
                toDocument(tid, result.createdOrganization()),
                source,
                result.movedChildCount(),
                result.movedMembershipCount());
    }

    /**
     * Assign department manager (Should S6) — ensures Membership for OrgScope.
     * 指定部门主管；确保 Membership 以纳入 OrgScope。
     */
    @PutMapping(PATH + "/{organizationId}/manager")
    public OrganizationDocument putManager(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("organizationId") String organizationId,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody(required = false) ManagerRequest body) {
        String tid = requireTenant(operator, tenantId);
        String oid = requireNonBlank(organizationId, "organizationId");
        if (body == null || body.managerSubjectId() == null || body.managerSubjectId().isBlank()) {
            throw new IllegalArgumentException("managerSubjectId required");
        }
        requireLinkedToTenant(tid, oid);
        OrganizationScope scope = resolveScope(operator, tid);
        requireOrganizationWritable(operator, tid, scope, oid, store.findActiveContainsParent(oid).orElse(null));
        store.setOrganizationManager(oid, body.managerSubjectId().trim());
        Organization org = store.listOrganizationsLinkedToTenant(tid).stream()
                .filter(o -> o.organizationId().equals(oid))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        audit.record(
                operator,
                "org.organization.manager.set",
                tid + "/" + oid + ":" + body.managerSubjectId().trim(),
                AuditOutcome.ALLOWED);
        return toDocument(tid, org);
    }

    /**
     * Clear department manager (Should S6). Does not end Membership.
     * 清除部门主管；不结束 Membership。
     */
    @DeleteMapping(PATH + "/{organizationId}/manager")
    public OrganizationDocument clearManager(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("organizationId") String organizationId,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        String tid = requireTenant(operator, tenantId);
        String oid = requireNonBlank(organizationId, "organizationId");
        requireLinkedToTenant(tid, oid);
        OrganizationScope scope = resolveScope(operator, tid);
        requireOrganizationWritable(operator, tid, scope, oid, store.findActiveContainsParent(oid).orElse(null));
        store.clearOrganizationManager(oid);
        Organization org = store.listOrganizationsLinkedToTenant(tid).stream()
                .filter(o -> o.organizationId().equals(oid))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        audit.record(
                operator,
                "org.organization.manager.clear",
                tid + "/" + oid,
                AuditOutcome.ALLOWED);
        return toDocument(tid, org);
    }

    private OrganizationDocument toDocument(String tenantId, Organization org) {
        String parent = store.findActiveContainsParent(org.organizationId()).orElse(null);
        int sortOrder = store.findActiveContainsSortOrder(org.organizationId());
        String manager = store.findActiveManagerSubjectId(org.organizationId()).orElse(null);
        return new OrganizationDocument(
                tenantId,
                org.organizationId(),
                parent,
                org.organizationName(),
                org.organizationState(),
                sortOrder,
                manager);
    }

    private static MembershipDocument membershipDocument(String tenantId, Membership row) {
        return new MembershipDocument(
                tenantId, row.subjectId(), row.organizationId(), row.membershipState());
    }

    /**
     * Resolve actor scope; null/blank subject -> NONE (fail-closed) -
     * 解析主体范围；无主体 -> NONE（失败关闭）。
     */
    private OrganizationScope resolveScope(OperatorPrincipal operator, String tenantId) {
        if (operator == null || operator.subjectId() == null || operator.subjectId().isBlank()) {
            return OrganizationScope.none();
        }
        return scopeResolver.resolveSelfAndDescendants(tenantId, operator.subjectId());
    }



    private static List<String> dedupePreserve(List<String> raw) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String id : raw) {
            if (id != null && !id.isBlank()) {
                set.add(id.trim());
            }
        }
        return new ArrayList<>(set);
    }

    /** Remove ids whose ancestor is also selected — 去掉已被选中祖先覆盖的节点。 */
    private List<String> dropCoveredDescendants(List<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            boolean covered = false;
            for (String other : ids) {
                if (other.equals(id)) {
                    continue;
                }
                if (store.containsSelfAndDescendants(other).contains(id)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                out.add(id);
            }
        }
        return out;
    }

    private void requireLinkedToTenant(String tenantId, String organizationId) {
        boolean linked = store.listOrganizationsLinkedToTenant(tenantId).stream()
                .anyMatch(o -> o.organizationId().equals(organizationId));
        if (!linked) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static String normalizeParent(String parentOrganizationId) {
        if (parentOrganizationId == null || parentOrganizationId.isBlank()) {
            return null;
        }
        return parentOrganizationId.trim();
    }

    private static boolean isMoveInScope(OrganizationScope scope, String organizationId, String newParent) {
        OrganizationScope effective = scope == null ? OrganizationScope.none() : scope;
        if (effective.isUnrestricted()) {
            return true;
        }
        if (!effective.contains(organizationId)) {
            return false;
        }
        if (newParent != null && !effective.contains(newParent)) {
            return false;
        }
        return true;
    }

    private void requireMoveWritable(
            OperatorPrincipal operator,
            String tenantId,
            OrganizationScope scope,
            String organizationId,
            String newParent) {
        if (isMoveInScope(scope, organizationId, newParent)) {
            return;
        }
        OrganizationScope effective = scope == null ? OrganizationScope.none() : scope;
        AccessDecision decision = AccessDecision.deny(
                operator == null ? null : operator.subjectId(),
                tenantId,
                effective,
                AccessResource.of("organization", organizationId),
                AccessAction.of("write"),
                OperatorPermission.ORG_WRITE.permissionName(),
                AccessDecision.DENY_ORG_OUT_OF_SCOPE);
        throw new AccessDecisionDeniedException(decision, OperatorPermission.ORG_WRITE.permissionName());
    }

    /**
     * Fail-closed write gate for org upsert (existing must be in scope; create under in-scope parent) -
     * 组织写入失败关闭：已存在须在范围内；新建须挂在范围内父节点下。
     */
    private void requireOrganizationWritable(
            OperatorPrincipal operator,
            String tenantId,
            OrganizationScope scope,
            String organizationId,
            String parentOrganizationId) {
        OrganizationScope effective = scope == null ? OrganizationScope.none() : scope;
        if (effective.isUnrestricted()) {
            return;
        }
        String oid = organizationId == null ? "" : organizationId.trim();
        String parent =
                parentOrganizationId == null || parentOrganizationId.isBlank()
                        ? null
                        : parentOrganizationId.trim();
        boolean exists = store.organizationExists(oid)
                && store.listOrganizationsLinkedToTenant(tenantId).stream()
                        .anyMatch(o -> o.organizationId().equals(oid));
        boolean allowed;
        if (exists) {
            allowed = effective.contains(oid);
        } else {
            allowed = parent != null && effective.contains(parent);
        }
        if (allowed) {
            return;
        }
        AccessDecision decision = AccessDecision.deny(
                operator == null ? null : operator.subjectId(),
                tenantId,
                effective,
                AccessResource.of("organization", oid),
                AccessAction.of("write"),
                OperatorPermission.ORG_WRITE.permissionName(),
                AccessDecision.DENY_ORG_OUT_OF_SCOPE);
        throw new AccessDecisionDeniedException(decision, OperatorPermission.ORG_WRITE.permissionName());
    }

    /** Fail-closed membership write via PolicyEngine + org scope — 成员写经 PolicyEngine + 组织范围失败关闭。 */
    private void requireMembershipOrganizationInScope(
            OperatorPrincipal operator, String tenantId, OrganizationScope scope, String organizationId) {
        OrganizationScope effective = scope == null ? OrganizationScope.none() : scope;
        String oid = organizationId == null ? "" : organizationId.trim();
        policyEngine.require(
                PolicyPrincipal.from(operator),
                OperatorPermission.ORG_WRITE.permissionName(),
                AccessAction.of("write"),
                PolicyResource.of("membership", oid)
                        .withAttribute(PolicyResource.ATTR_ORGANIZATION_ID, oid)
                        .withAttribute(PolicyResource.ATTR_TENANT_ID, tenantId),
                PolicyContext.of(tenantId, false, effective));
    }

    /**
     * Null scope -> exclude (fail-closed; never treat unspecified as UNRESTRICTED) -
     * null 范围视为不在范围内（失败关闭；未指定绝不当 UNRESTRICTED）。
     */
    private static boolean inScopeOrUnspecified(OrganizationScope scope, String organizationId) {
        if (scope == null) {
            return false;
        }
        return scope.contains(organizationId);
    }

    private static String requireTenantIdParam(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId required");
        }
        return tenantId.trim();
    }

    private String requireTenant(OperatorPrincipal operator, String tenantId) {
        String tid = requireTenantIdParam(tenantId);
        tenantAccess.requireGranted(operator, tid);
        return tid;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return value.trim();
    }

    public record OrganizationsDocument(List<OrganizationDocument> organizations) {}

    public record OrganizationDocument(
            String tenantId,
            String organizationId,
            String parentOrganizationId,
            String organizationName,
            String organizationState,
            int sortOrder,
            String managerSubjectId) {}

    public record MembershipsDocument(List<MembershipDocument> memberships) {}

    public record MembershipDocument(
            String tenantId, String subjectId, String organizationId, String membershipState) {}

    public record OrganizationWriteRequest(
            String parentOrganizationId, String organizationName, String organizationState) {}

    public record MembershipWriteRequest(String subjectId, String organizationId, String membershipState) {}

    public record MoveRequest(String newParentOrganizationId) {}

    public record MovePreviewDocument(
            String organizationId,
            String fromParentOrganizationId,
            String toParentOrganizationId,
            int subtreeOrganizationCount,
            int directMembershipCount,
            boolean wouldCycle,
            boolean inScope) {}


    public record BatchMoveRequest(List<String> organizationIds, String newParentOrganizationId) {}

    public record BatchMovePreviewDocument(
            List<MovePreviewDocument> items,
            String toParentOrganizationId,
            boolean anyCycle,
            boolean allInScope,
            int totalSubtreeOrganizationCount,
            int totalDirectMembershipCount) {}

    public record BatchMoveResultDocument(List<OrganizationDocument> moved) {}

    public record ChildrenOrderRequest(List<String> orderedChildIds) {}

    public record MergeRequest(String targetOrganizationId) {}

    public record MergePreviewDocument(
            String sourceOrganizationId,
            String targetOrganizationId,
            int movedMembershipCount,
            int reparentedChildCount,
            int sourceSubtreeOrganizationCount,
            boolean wouldCycle,
            boolean inScope) {}

    public record MergeResultDocument(
            OrganizationDocument endedSource,
            OrganizationDocument target,
            int movedMembershipCount,
            int reparentedChildCount) {}

    public record SplitRequest(
            String newOrganizationId,
            String newOrganizationName,
            String newParentOrganizationId,
            List<String> childOrganizationIds,
            List<String> memberSubjectIds) {}

    public record SplitPreviewDocument(
            String sourceOrganizationId,
            String newOrganizationId,
            String newParentOrganizationId,
            int childCount,
            int memberCount,
            boolean childrenValid,
            boolean inScope) {}

    public record SplitResultDocument(
            OrganizationDocument created,
            String sourceOrganizationId,
            int movedChildCount,
            int movedMembershipCount) {}

    public record ManagerRequest(String managerSubjectId) {}

}

