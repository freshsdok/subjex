package com.subjex.platform.app.security;

import com.subjex.platform.contract.tenant.TenantGuard;

/**
 * DeclarationAccess — 声明访问：按 YAML 上的权限名与租户标志做失败关闭检查，产出可解释 {@link AccessDecision}。
 * <p>
 * Reuses the operator's granted permission names. Missing or blank permission denies.
 * When {@code tenantScoped} is true, the tenant header must pass {@link TenantGuard}
 * and {@link OperatorTenantAccess} (operator–tenant grant).
 * Denied checks throw {@link AccessDecisionDeniedException} with a full decision record.
 * Org scope is applied when callers pass OrgScope (AX-2).
 * 复用操作员已授予的权限名。权限缺失或空白即拒绝。
 * {@code tenantScoped} 为 true 时，租户头必须通过 {@link TenantGuard} 与 {@link OperatorTenantAccess}。
 * 拒绝抛出带完整决策的 {@link AccessDecisionDeniedException}。组织范围由调用方传入（AX-2）。
 */
public final class DeclarationAccess {

    private DeclarationAccess() {}

    /**
     * Full declaration gate: permission + optional tenant — 完整声明门禁：权限 + 可选租户。
     *
     * @return allow decision when permitted
     */
    public static AccessDecision require(
            OperatorPrincipal operator,
            String permission,
            boolean tenantScoped,
            String tenantId,
            TenantGuard tenantGuard,
            OperatorTenantAccess tenantAccess,
            AccessResource resource,
            AccessAction action) {
        return AccessChecker.require(
                operator, permission, tenantScoped, tenantId, tenantGuard, tenantAccess, resource, action);
    }

    /**
     * Deny unless the operator holds the named permission — 操作员未持有该具名权限则拒绝。
     *
     * @deprecated prefer {@link #require} with resource/action for explainable decisions
     */
    public static void requirePermission(OperatorPrincipal operator, String permission) {
        AccessDecision decision = AccessChecker.evaluate(
                operator,
                permission,
                false,
                null,
                null,
                null,
                AccessResource.of("declaration", ""),
                AccessAction.of("check"));
        if (!decision.allowed()) {
            throw new AccessDecisionDeniedException(decision, permission);
        }
    }

    /**
     * When the declaration is tenant-scoped, require tenant id and operator grant —
     * 声明按租户隔离时要求租户标识且操作员获准代表该租户。
     */
    public static AccessDecision requireTenantWhenScoped(
            TenantGuard tenantGuard,
            OperatorTenantAccess tenantAccess,
            OperatorPrincipal operator,
            boolean tenantScoped,
            String tenantId) {
        return requireTenantWhenScoped(
                tenantGuard,
                tenantAccess,
                operator,
                tenantScoped,
                tenantId,
                AccessResource.of("declaration", ""),
                AccessAction.of("tenant"),
                null);
    }

    /**
     * Tenant-scoped gate with resource/action context — 带资源/动作上下文的租户门禁。
     */
    public static AccessDecision requireTenantWhenScoped(
            TenantGuard tenantGuard,
            OperatorTenantAccess tenantAccess,
            OperatorPrincipal operator,
            boolean tenantScoped,
            String tenantId,
            AccessResource resource,
            AccessAction action,
            String matchedPermission) {
        AccessDecision decision = AccessChecker.evaluateTenantWhenScoped(
                operator, tenantScoped, tenantId, tenantGuard, tenantAccess, resource, action, matchedPermission);
        if (!decision.allowed()) {
            throw new AccessDecisionDeniedException(decision, matchedPermission == null ? "permission" : matchedPermission);
        }
        return decision;
    }
}
