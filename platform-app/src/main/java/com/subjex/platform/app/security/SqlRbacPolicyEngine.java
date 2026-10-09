package com.subjex.platform.app.security;

import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.Objects;
import java.util.Set;

/**
 * SqlRbacPolicyEngine — {@link PolicyEngine} 首适配器：委托现有 {@link AccessChecker} / SQL RBAC。
 * <p>
 * Does not fork permission or org-scope logic. Bridges {@link PolicyPrincipal} to a lightweight
 * {@link OperatorPrincipal} for AccessChecker. Resource {@link PolicyResource#ATTR_ORG_UNIT_ID}
 * feeds org-scope checks. No Cedar/Casbin dependency.
 * 不复制权限/组织逻辑；主体桥到轻量 OperatorPrincipal；资源 orgUnitId 供范围核对。
 */
public final class SqlRbacPolicyEngine implements PolicyEngine {

    private final TenantGuard tenantGuard;
    private final OperatorTenantAccess tenantAccess;

    public SqlRbacPolicyEngine(TenantGuard tenantGuard, OperatorTenantAccess tenantAccess) {
        this.tenantGuard = Objects.requireNonNull(tenantGuard, "tenantGuard");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
    }

    @Override
    public AccessDecision evaluate(
            PolicyPrincipal principal,
            String requiredPermission,
            AccessAction action,
            PolicyResource resource,
            PolicyContext context) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(resource, "resource");
        PolicyContext ctx = context == null ? PolicyContext.unscoped() : context;
        OperatorPrincipal bridge = bridge(principal);
        String tenantId = ctx.tenantId();
        if (tenantId == null) {
            tenantId = resource.attribute(PolicyResource.ATTR_TENANT_ID);
        }
        return AccessChecker.evaluate(
                bridge,
                requiredPermission,
                ctx.tenantScoped(),
                tenantId,
                tenantGuard,
                tenantAccess,
                AccessResource.of(resource.kind(), resource.id()),
                action,
                ctx.orgScope(),
                resource.orgUnitId());
    }

    /**
     * AccessChecker only reads subjectId + permissionNames — AccessChecker 只读主体与权限名。
     */
    static OperatorPrincipal bridge(PolicyPrincipal principal) {
        if (principal == null) {
            return null;
        }
        Set<String> perms = principal.permissionNames();
        return new OperatorPrincipal(
                "",
                "",
                "",
                principal.subjectId(),
                perms == null ? Set.of() : perms,
                true);
    }
}
