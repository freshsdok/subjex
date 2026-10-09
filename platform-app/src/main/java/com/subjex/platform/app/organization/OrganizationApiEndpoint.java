package com.subjex.platform.app.organization;

import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.org.JdbcOrgDirectory;
import com.subjex.platform.app.security.AccessAction;
import com.subjex.platform.app.security.AccessDecision;
import com.subjex.platform.app.security.AccessDecisionDeniedException;
import com.subjex.platform.app.security.AccessResource;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPermission;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.OrgScope;
import com.subjex.platform.app.security.PolicyContext;
import com.subjex.platform.app.security.PolicyEngine;
import com.subjex.platform.app.security.PolicyPrincipal;
import com.subjex.platform.app.security.PolicyResource;
import com.subjex.platform.contract.audit.AuditOutcome;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * OrganizationApiEndpoint — O5 Organization ontology JSON API.
 * <p>
 * Paths under {@code /api/v1/organizations}. Needs {@code org.read} / {@code org.write}
 * (same as legacy {@code /api/v1/org/**}). Writes hit ontology tables then reverse
 * write-through to legacy {@code org_unit}/{@code org_membership} for O3 dual-read.
 * Scope uses organization ids ({@link JdbcOrgDirectory#resolveOrganizationSelfAndDescendants}).
 * <p>
 * O5 组织本体 JSON。权限与旧 {@code /api/v1/org} 相同；写落新表并反向写透旧表。
 */
@RestController
public class OrganizationApiEndpoint {

    public static final String PATH = JsonApi.BASE + "/organizations";

    private final JdbcOrganizationStore store;
    private final OrganizationOntologyBackfill backfill;
    private final JdbcOrgDirectory directory;
    private final OperatorTenantAccess tenantAccess;
    private final OperatorActionAudit audit;
    private final PolicyEngine policyEngine;

    public OrganizationApiEndpoint(
            JdbcOrganizationStore store,
            OrganizationOntologyBackfill backfill,
            JdbcOrgDirectory directory,
            OperatorTenantAccess tenantAccess,
            OperatorActionAudit audit,
            PolicyEngine policyEngine) {
        this.store = Objects.requireNonNull(store, "store");
        this.backfill = Objects.requireNonNull(backfill, "backfill");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine");
    }

    @GetMapping(PATH)
    public OrganizationsDocument list(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        String tid = requireTenantIdParam(tenantId);
        OrgScope scope = resolveScope(operator, tid);
        List<OrganizationDocument> organizations = store.listOrganizationsLinkedToTenant(tid).stream()
                .filter(o -> inScopeOrUnspecified(scope, o.organizationId()))
                .map(o -> toDocument(tid, o))
                .toList();
        return new OrganizationsDocument(organizations);
    }

    @GetMapping(PATH + "/{organizationId}")
    public OrganizationDocument getOne(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("organizationId") String organizationId,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        String tid = requireTenantIdParam(tenantId);
        OrgScope scope = resolveScope(operator, tid);
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
        OrgScope scope = resolveScope(operator, tid);
        requireOrganizationWritable(operator, tid, scope, oid, body.parentOrganizationId());
        Organization saved = store.upsertOrganization(oid, body.organizationName(), body.organizationState());
        store.upsertTenantOrganization(tid, oid, JdbcOrganizationStore.STATE_ACTIVE);
        store.setContainsParent(oid, body.parentOrganizationId());
        backfill.syncOrganizationToLegacy(tid, saved, body.parentOrganizationId());
        audit.record(operator, "organization.upsert", tid + "/" + saved.organizationId(), AuditOutcome.ALLOWED);
        return toDocument(tid, saved);
    }

    @GetMapping(PATH + "/memberships")
    public MembershipsDocument memberships(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "subjectId", required = false) String subjectId) {
        String tid = requireTenantIdParam(tenantId);
        OrgScope scope = resolveScope(operator, tid);
        List<MembershipDocument> memberships = store.listMembershipsForTenant(tid, subjectId).stream()
                .filter(m -> inScopeOrUnspecified(scope, m.organizationId()))
                .map(m -> membershipDocument(tid, m))
                .toList();
        return new MembershipsDocument(memberships);
    }

    @PutMapping(PATH + "/memberships")
    public MembershipDocument upsertMembership(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody MembershipWriteRequest body) {
        String tid = requireTenant(operator, tenantId);
        if (body == null) {
            throw new IllegalArgumentException("body required");
        }
        OrgScope scope = resolveScope(operator, tid);
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
        backfill.syncMembershipToLegacy(tid, saved);
        audit.record(
                operator,
                "organization.membership.upsert",
                tid + "/" + saved.subjectId() + "/" + saved.organizationId(),
                AuditOutcome.ALLOWED);
        return membershipDocument(tid, saved);
    }

    @DeleteMapping(PATH + "/memberships")
    public void removeMembership(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "subjectId", required = false) String subjectId,
            @RequestParam(value = "organizationId", required = false) String organizationId) {
        String tid = requireTenant(operator, tenantId);
        OrgScope scope = resolveScope(operator, tid);
        requireMembershipOrganizationInScope(operator, tid, scope, organizationId);
        if (!store.endMembership(subjectId, organizationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        backfill.endMembershipToLegacy(tid, subjectId, organizationId);
        audit.record(
                operator,
                "organization.membership.remove",
                tid + "/" + subjectId.trim() + "/" + organizationId.trim(),
                AuditOutcome.ALLOWED);
    }

    private OrganizationDocument toDocument(String tenantId, Organization org) {
        String parent = store.findActiveContainsParent(org.organizationId()).orElse(null);
        return new OrganizationDocument(
                tenantId,
                org.organizationId(),
                parent,
                org.organizationName(),
                org.organizationState());
    }

    private static MembershipDocument membershipDocument(String tenantId, Membership row) {
        return new MembershipDocument(
                tenantId, row.subjectId(), row.organizationId(), row.membershipState());
    }

    private OrgScope resolveScope(OperatorPrincipal operator, String tenantId) {
        if (operator == null || operator.subjectId() == null || operator.subjectId().isBlank()) {
            return OrgScope.none();
        }
        return directory.resolveOrganizationSelfAndDescendants(tenantId, operator.subjectId());
    }

    private void requireOrganizationWritable(
            OperatorPrincipal operator,
            String tenantId,
            OrgScope scope,
            String organizationId,
            String parentOrganizationId) {
        OrgScope effective = scope == null ? OrgScope.none() : scope;
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

    private void requireMembershipOrganizationInScope(
            OperatorPrincipal operator, String tenantId, OrgScope scope, String organizationId) {
        OrgScope effective = scope == null ? OrgScope.none() : scope;
        String oid = organizationId == null ? "" : organizationId.trim();
        policyEngine.require(
                PolicyPrincipal.from(operator),
                OperatorPermission.ORG_WRITE.permissionName(),
                AccessAction.of("write"),
                PolicyResource.of("membership", oid)
                        .withAttribute(PolicyResource.ATTR_ORG_UNIT_ID, oid)
                        .withAttribute(PolicyResource.ATTR_TENANT_ID, tenantId),
                PolicyContext.of(tenantId, false, effective));
    }

    private static boolean inScopeOrUnspecified(OrgScope scope, String organizationId) {
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
            String organizationState) {}

    public record MembershipsDocument(List<MembershipDocument> memberships) {}

    public record MembershipDocument(
            String tenantId, String subjectId, String organizationId, String membershipState) {}

    public record OrganizationWriteRequest(
            String parentOrganizationId, String organizationName, String organizationState) {}

    public record MembershipWriteRequest(String subjectId, String organizationId, String membershipState) {}
}
