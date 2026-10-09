package com.subjex.platform.app.org;

import com.subjex.platform.app.api.JsonApi;
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
import com.subjex.platform.app.organization.OrganizationApiEndpoint;
import jakarta.servlet.http.HttpServletResponse;
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
 * OrgApiEndpoint — legacy org_unit JSON (deprecated since O5).
 * <p>
 * Prefer {@link com.subjex.platform.app.organization.OrganizationApiEndpoint}
 * at {@code /api/v1/organizations}. This controller remains for compatibility;
 * responses carry {@code Deprecation} / {@code Link} / {@code Warning} via
 * response Deprecation headers.
 * <p>
 * GET needs {@code org.read}. PUT/DELETE need {@code org.write} plus an operator–tenant grant.
 * When the actor has ACTIVE memberships, responses and writes are limited to
 * {@link OrgScope#MODE_SELF_AND_DESCENDANTS}. No ACTIVE memberships → {@link OrgScope#none()}
 * (fail-closed lists/writes; never silently {@link OrgScope#MODE_UNRESTRICTED}).
 * Membership write scope uses {@link PolicyEngine} (SQL RBAC adapter).
 * Missing/blank {@code tenantId} is 400. Optional {@code subjectId} on GET memberships narrows via
 * {@link JdbcOrgDirectory#listMembershipsForSubject}.
 * <p>
 * 旧 org_unit JSON（O5 起弃用）。请改用 {@code /api/v1/organizations}。本控制器仍兼容；响应带弃用头。
 */
@Deprecated
@RestController
public class OrgApiEndpoint {

    /** JSON base for org APIs — 组织 JSON 根路径。 */
    public static final String PATH = JsonApi.BASE + "/org";

    private final JdbcOrgDirectory directory;
    private final OperatorTenantAccess tenantAccess;
    private final OperatorActionAudit audit;
    private final PolicyEngine policyEngine;

    public OrgApiEndpoint(
            JdbcOrgDirectory directory,
            OperatorTenantAccess tenantAccess,
            OperatorActionAudit audit,
            PolicyEngine policyEngine) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine");
    }

    @GetMapping(PATH + "/units")
    public UnitsDocument units(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            HttpServletResponse response) {
        markDeprecated(response);
        String tid = requireTenantIdParam(tenantId);
        OrgScope scope = resolveScope(operator, tid);
        List<OrgUnitDocument> units = directory.listUnits(tid).stream()
                .filter(u -> inScopeOrUnspecified(scope, u.orgUnitId()))
                .map(OrgApiEndpoint::unit)
                .toList();
        return new UnitsDocument(units);
    }

    @GetMapping(PATH + "/memberships")
    public MembershipsDocument memberships(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "subjectId", required = false) String subjectId,
            HttpServletResponse response) {
        markDeprecated(response);
        String tid = requireTenantIdParam(tenantId);
        OrgScope scope = resolveScope(operator, tid);
        List<OrgMembership> rows = subjectId == null || subjectId.isBlank()
                ? directory.listMemberships(tid)
                : directory.listMembershipsForSubject(tid, subjectId);
        List<MembershipDocument> memberships = rows.stream()
                .filter(m -> inScopeOrUnspecified(scope, m.orgUnitId()))
                .map(OrgApiEndpoint::membership)
                .toList();
        return new MembershipsDocument(memberships);
    }

    @PutMapping(PATH + "/units/{orgUnitId}")
    public OrgUnitDocument upsertUnit(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @PathVariable("orgUnitId") String orgUnitId,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody UnitWriteRequest body,
            HttpServletResponse response) {
        markDeprecated(response);
        String tid = requireTenant(operator, tenantId);
        if (body == null) {
            throw new IllegalArgumentException("body required");
        }
        OrgScope scope = resolveScope(operator, tid);
        requireUnitWritable(operator, tid, scope, orgUnitId, body.parentOrgUnitId());
        OrgUnit saved = directory.upsertUnit(
                tid, orgUnitId, body.parentOrgUnitId(), body.unitName(), body.unitState());
        audit.record(operator, "org.unit.upsert", tid + "/" + saved.orgUnitId(), AuditOutcome.ALLOWED);
        return unit(saved);
    }

    @PutMapping(PATH + "/memberships")
    public MembershipDocument upsertMembership(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody MembershipWriteRequest body,
            HttpServletResponse response) {
        markDeprecated(response);
        String tid = requireTenant(operator, tenantId);
        if (body == null) {
            throw new IllegalArgumentException("body required");
        }
        OrgScope scope = resolveScope(operator, tid);
        requireMembershipUnitInScope(operator, tid, scope, body.orgUnitId());
        OrgMembership saved =
                directory.upsertMembership(tid, body.subjectId(), body.orgUnitId(), body.membershipState());
        audit.record(
                operator,
                "org.membership.upsert",
                tid + "/" + saved.subjectId() + "/" + saved.orgUnitId(),
                AuditOutcome.ALLOWED);
        return membership(saved);
    }

    @DeleteMapping(PATH + "/memberships")
    public void removeMembership(
            @AuthenticationPrincipal OperatorPrincipal operator,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "subjectId", required = false) String subjectId,
            @RequestParam(value = "orgUnitId", required = false) String orgUnitId,
            HttpServletResponse response) {
        markDeprecated(response);
        String tid = requireTenant(operator, tenantId);
        OrgScope scope = resolveScope(operator, tid);
        requireMembershipUnitInScope(operator, tid, scope, orgUnitId);
        if (!directory.removeMembership(tid, subjectId, orgUnitId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        audit.record(
                operator,
                "org.membership.remove",
                tid + "/" + subjectId.trim() + "/" + orgUnitId.trim(),
                AuditOutcome.ALLOWED);
    }

    private OrgScope resolveScope(OperatorPrincipal operator, String tenantId) {
        if (operator == null || operator.subjectId() == null || operator.subjectId().isBlank()) {
            return OrgScope.none();
        }
        return directory.resolveSelfAndDescendants(tenantId, operator.subjectId());
    }

    private void requireUnitWritable(
            OperatorPrincipal operator,
            String tenantId,
            OrgScope scope,
            String orgUnitId,
            String parentOrgUnitId) {
        OrgScope effective = scope == null ? OrgScope.none() : scope;
        if (effective.isUnrestricted()) {
            return;
        }
        String uid = orgUnitId == null ? "" : orgUnitId.trim();
        String parent = parentOrgUnitId == null || parentOrgUnitId.isBlank() ? null : parentOrgUnitId.trim();
        boolean exists = directory.unitExists(tenantId, uid);
        boolean allowed;
        if (exists) {
            allowed = effective.contains(uid);
        } else {
            // Creating: must attach under an in-scope parent (no new roots for scoped actors).
            allowed = parent != null && effective.contains(parent);
        }
        if (allowed) {
            return;
        }
        AccessDecision decision = AccessDecision.deny(
                operator == null ? null : operator.subjectId(),
                tenantId,
                effective,
                AccessResource.of("org_unit", uid),
                AccessAction.of("write"),
                OperatorPermission.ORG_WRITE.permissionName(),
                AccessDecision.DENY_ORG_OUT_OF_SCOPE);
        throw new AccessDecisionDeniedException(decision, OperatorPermission.ORG_WRITE.permissionName());
    }

    private void requireMembershipUnitInScope(
            OperatorPrincipal operator, String tenantId, OrgScope scope, String orgUnitId) {
        OrgScope effective = scope == null ? OrgScope.none() : scope;
        String uid = orgUnitId == null ? "" : orgUnitId.trim();
        policyEngine.require(
                PolicyPrincipal.from(operator),
                OperatorPermission.ORG_WRITE.permissionName(),
                AccessAction.of("write"),
                PolicyResource.of("org_membership", uid)
                        .withAttribute(PolicyResource.ATTR_ORG_UNIT_ID, uid)
                        .withAttribute(PolicyResource.ATTR_TENANT_ID, tenantId),
                PolicyContext.of(tenantId, false, effective));
    }

    private static boolean inScopeOrUnspecified(OrgScope scope, String orgUnitId) {
        if (scope == null) {
            return false;
        }
        return scope.contains(orgUnitId);
    }


    private static void markDeprecated(HttpServletResponse response) {
        if (response == null) {
            return;
        }
        response.setHeader("Deprecation", "true");
        response.setHeader(
                "Link",
                "<" + OrganizationApiEndpoint.PATH + ">; rel=\"successor-version\"");
        response.setHeader(
                "Warning",
                "299 - \"Deprecated: use " + OrganizationApiEndpoint.PATH + " (O5 Organization ontology)\"");
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

    private static OrgUnitDocument unit(OrgUnit row) {
        return new OrgUnitDocument(
                row.tenantId(), row.orgUnitId(), row.parentOrgUnitId(), row.unitName(), row.unitState());
    }

    private static MembershipDocument membership(OrgMembership row) {
        return new MembershipDocument(
                row.tenantId(), row.subjectId(), row.orgUnitId(), row.membershipState());
    }

    /** UnitsDocument — 某租户的组织单元列表。 */
    public record UnitsDocument(List<OrgUnitDocument> units) {}

    /** OrgUnitDocument — 一个组织单元。 */
    public record OrgUnitDocument(
            String tenantId, String orgUnitId, String parentOrgUnitId, String unitName, String unitState) {}

    /** MembershipsDocument — 成员关系列表。 */
    public record MembershipsDocument(List<MembershipDocument> memberships) {}

    /** MembershipDocument — 一条主体-组织单元成员关系。 */
    public record MembershipDocument(
            String tenantId, String subjectId, String orgUnitId, String membershipState) {}

    /** Unit write body — 组织单元写入正文。 */
    public record UnitWriteRequest(String parentOrgUnitId, String unitName, String unitState) {}

    /** Membership write body — 成员关系写入正文。 */
    public record MembershipWriteRequest(String subjectId, String orgUnitId, String membershipState) {}
}
