package com.subjex.platform.app.organization;

import com.subjex.platform.app.security.OrganizationScope;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * OrganizationScopeResolver — O8-1 scope from Membership + OrganizationRelation(CONTAINS).
 * <p>
 * Reads ontology tables only (no {@code JdbcOrgDirectory}, no {@code org_unit_organization_map}).
 * Tenant filters which ACTIVE memberships count as roots via {@code TenantOrganization};
 * expansion is ACTIVE CONTAINS only. Empty ACTIVE memberships → {@link OrganizationScope#none()}
 * (fail-closed; never silently UNRESTRICTED). Membership has no tenant_id.
 * <p>
 * 仅读本体表；租户经 TenantOrganization 过滤根；下级仅 CONTAINS；无成员 → NONE。
 */
public final class OrganizationScopeResolver {

    private final JdbcOrganizationStore store;

    public OrganizationScopeResolver(JdbcOrganizationStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * {@link OrganizationScope#MODE_SELF} — ACTIVE membership organization ids linked to the tenant.
     */
    public OrganizationScope resolveSelf(String tenantId, String subjectId) {
        List<String> roots = activeMembershipOrganizationIds(tenantId, subjectId);
        if (roots.isEmpty()) {
            return OrganizationScope.none();
        }
        return OrganizationScope.self(roots);
    }

    /**
     * {@link OrganizationScope#MODE_SELF_AND_DESCENDANTS} — SELF roots plus ACTIVE CONTAINS closure.
     */
    public OrganizationScope resolveSelfAndDescendants(String tenantId, String subjectId) {
        List<String> roots = activeMembershipOrganizationIds(tenantId, subjectId);
        if (roots.isEmpty()) {
            return OrganizationScope.none();
        }
        Set<String> expanded = new HashSet<>();
        for (String root : roots) {
            expanded.addAll(store.containsSelfAndDescendants(root));
        }
        return OrganizationScope.selfAndDescendants(roots, expanded);
    }

    private List<String> activeMembershipOrganizationIds(String tenantId, String subjectId) {
        String tid = requireNonBlank(tenantId, "tenantId");
        String sid = requireNonBlank(subjectId, "subjectId");
        Set<String> ordered = new TreeSet<>();
        for (Membership membership : store.listMembershipsForTenant(tid, sid)) {
            if (JdbcOrganizationStore.STATE_ACTIVE.equals(membership.membershipState())) {
                ordered.add(membership.organizationId());
            }
        }
        return new ArrayList<>(ordered);
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return value.trim();
    }
}
