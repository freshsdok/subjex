package com.subjex.platform.app.security;

import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.Objects;
import java.util.Set;

/**
 * SqlRbacPolicyEngine — {@link PolicyEngine} 首适配器：委托现有 {@link AccessChecker} / SQL RBAC。
 * <p>
 * Does not fork permission or org-scope logic. Bridges {@link PolicyPrincipal} to a lightweight
 * {@link OperatorPrincipal} for AccessChecker. Resource {@link PolicyResource#organizationId()} feeds organization-scope checks. Context vs resource tenant mismatch → {@link AccessDecision#DENY_TENANT_MISMATCH}.
 * No Cedar/Casbin dependency.
 * 不复制权限/组织逻辑；主体桥到轻量 OperatorPrincipal；资源 organizationId 供范围核对；租户不一致拒绝。
 */
public final class SqlRbacPolicyEngine implements PolicyEngine {

    private final TenantGuard tenantGuard;
    private final OperatorTenantAccess tenantAccess;

    public SqlRbacPolicyEngine(TenantGuard tenantGuard, OperatorTenantAccess tenantAccess) {
        this.tenantGuard = Objects.requireNonNull(tenantGuard, "tenantGuard");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
    }

    /**
     * Delegates to AccessChecker; context/resource tenant mismatch -> DENY_TENANT_MISMATCH (fail-closed) -
     * 委托 AccessChecker；上下文与资源租户不一致则 DENY_TENANT_MISMATCH（失败关闭）。
     */
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
        String ctxTenant = ctx.tenantId();
        String resourceTenant = resource.attribute(PolicyResource.ATTR_TENANT_ID);
        if (ctxTenant != null
                && resourceTenant != null
                && !resourceTenant.isBlank()
                && !ctxTenant.equals(resourceTenant.trim())) {
            return AccessDecision.deny(
                    principal == null ? null : principal.subjectId(),
                    ctxTenant,
                    ctx.orgScope(),
                    AccessResource.of(resource.kind(), resource.id()),
                    action,
                    requiredPermission == null || requiredPermission.isBlank() ? null : requiredPermission,
                    AccessDecision.DENY_TENANT_MISMATCH);
        }
        String tenantId = ctxTenant;
        if (tenantId == null) {
            tenantId = resourceTenant;
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
                resource.organizationId());
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
