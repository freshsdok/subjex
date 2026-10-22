package com.subjex.platform.app.security;

import com.subjex.platform.contract.tenant.TenantGuard;
import java.util.Objects;

/**
 * DeclarationAccess — 声明访问：按 YAML 上的权限名与租户标志做失败关闭检查，产出可解释 {@link AccessDecision}。
 * <p>
 * Reuses the operator's granted permission names. Missing or blank permission denies.
 * When {@code tenantScoped} is true, the tenant header must pass {@link TenantGuard}
 * and {@link OperatorTenantAccess} (operator–tenant grant).
 * Denied checks throw {@link AccessDecisionDeniedException} with a full decision record.
 * Org scope is applied when callers pass OrganizationScope (AX-2 / O8).
 * Prefer {@link #requireVia(PolicyEngine, ...)} so {@code platform.authz.engine=cedar} applies
 * (AuthZ-1e); legacy {@link #require} still hits {@link AccessChecker} directly.
 * 复用操作员已授予的权限名。权限缺失或空白即拒绝。
 * {@code tenantScoped} 为 true 时，租户头必须通过 {@link TenantGuard} 与 {@link OperatorTenantAccess}。
 * 拒绝抛出带完整决策的 {@link AccessDecisionDeniedException}。组织范围由调用方传入（AX-2）。
 * 优先 requireVia 走 PolicyEngine（AuthZ-1e）；旧 require 仍直调 AccessChecker。
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
     * Same gate as {@link #require} but via {@link PolicyEngine} (Cedar when default engine).
     * Maps declaration permission + tenantScoped into {@link PolicyPrincipal}/{@link PolicyResource}/
     * {@link PolicyContext}. Prefer this over {@link #require} for new/widened callers (AuthZ-1e/1f).
     * <p>
     * 与 {@link #require} 同门禁，但经 PolicyEngine（默认 Cedar）。AuthZ-1e/1f 优先此路径。
     */
    public static AccessDecision requireVia(
            PolicyEngine policyEngine,
            OperatorPrincipal operator,
            String permission,
            boolean tenantScoped,
            String tenantId,
            AccessResource resource,
            AccessAction action) {
        Objects.requireNonNull(policyEngine, "policyEngine");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(action, "action");
        String tid = tenantId == null || tenantId.isBlank() ? null : tenantId.trim();
        PolicyResource policyResource = PolicyResource.of(resource.kind(), resource.id());
        if (tid != null) {
            policyResource = policyResource.withAttribute(PolicyResource.ATTR_TENANT_ID, tid);
        }
        return policyEngine.require(
                PolicyPrincipal.from(operator),
                permission,
                action,
                policyResource,
                PolicyContext.of(tid, tenantScoped, null));
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
