package com.subjex.platform.app.security;

import com.subjex.platform.contract.tenant.TenantGuard;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.util.Objects;

/**
 * AccessChecker — 可解释上下文权限判定：权限成员 + 可选租户授权 + 可选组织范围（本部门及下级）。
 * <p>
 * Returns an {@link AccessDecision} every time (allow or deny). Does not throw; callers
 * that need fail-closed use {@link #require} or {@link DeclarationAccess}.
 * No new role names; no Zanzibar. External-shaped entry is {@link PolicyEngine}
 * (SqlRbacPolicyEngine delegates here). Cedar/Casbin jars are later swap-ins behind that port.
 * 每次返回 {@link AccessDecision}（允许或拒绝）。本身不抛；需失败关闭时用 require / DeclarationAccess。
 * 不加角色名；不上 Zanzibar。外置形状入口见 {@link PolicyEngine}；完整 Cedar/Casbin 后置换入。
 */
public final class AccessChecker {

    private AccessChecker() {}

    /**
     * Permission membership, then tenant when scoped — 先权限成员，再按需租户。
     */
    public static AccessDecision evaluate(
            OperatorPrincipal operator,
            String permission,
            boolean tenantScoped,
            String tenantId,
            TenantGuard tenantGuard,
            OperatorTenantAccess tenantAccess,
            AccessResource resource,
            AccessAction action) {
        return evaluate(
                operator,
                permission,
                tenantScoped,
                tenantId,
                tenantGuard,
                tenantAccess,
                resource,
                action,
                null,
                null);
    }

    /**
     * Full evaluate with optional org scope + resource unit — 带可选组织范围与资源单元的完整判定。
     * <p>
     * When {@code resourceOrgUnitId} is non-blank: null/unspecified scope → fail-closed
     * {@link AccessDecision#DENY_ORG_SCOPE_MISSING}; {@link OrgScope#MODE_NONE} →
     * {@link AccessDecision#DENY_ORG_OUT_OF_SCOPE}; {@link OrgScope#MODE_UNRESTRICTED} → allow;
     * otherwise the unit must be inside the scope. Blank resource unit skips the org filter.
     * 资源组织 id 非空时：未指定 fail-closed；NONE 拒绝；UNRESTRICTED 放行；否则须落在范围内。
     */
    public static AccessDecision evaluate(
            OperatorPrincipal operator,
            String permission,
            boolean tenantScoped,
            String tenantId,
            TenantGuard tenantGuard,
            OperatorTenantAccess tenantAccess,
            AccessResource resource,
            AccessAction action,
            OrgScope orgScope,
            String resourceOrgUnitId) {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(action, "action");
        String subjectId = operator == null ? null : operator.subjectId();

        if (permission == null || permission.isBlank()) {
            return AccessDecision.deny(
                    subjectId,
                    tenantId,
                    orgScope,
                    resource,
                    action,
                    null,
                    AccessDecision.DENY_PERMISSION_BLANK);
        }
        if (operator == null || !operator.permissionNames().contains(permission)) {
            return AccessDecision.deny(
                    subjectId,
                    tenantId,
                    orgScope,
                    resource,
                    action,
                    null,
                    AccessDecision.DENY_PERMISSION_MISSING);
        }

        AccessDecision afterTenant;
        if (!tenantScoped) {
            afterTenant = AccessDecision.allow(subjectId, tenantId, orgScope, resource, action, permission);
        } else {
            afterTenant = evaluateTenantAfterPermission(
                    operator, subjectId, permission, tenantId, tenantGuard, tenantAccess, resource, action, orgScope);
        }
        if (!afterTenant.allowed()) {
            return afterTenant;
        }
        return applyOrgScope(afterTenant, orgScope, resourceOrgUnitId);
    }

    /**
     * Tenant gate only (permission already established) — 仅租户门禁（权限已确认）。
     */
    public static AccessDecision evaluateTenantWhenScoped(
            OperatorPrincipal operator,
            boolean tenantScoped,
            String tenantId,
            TenantGuard tenantGuard,
            OperatorTenantAccess tenantAccess,
            AccessResource resource,
            AccessAction action,
            String matchedPermission) {
        return evaluateTenantWhenScoped(
                operator,
                tenantScoped,
                tenantId,
                tenantGuard,
                tenantAccess,
                resource,
                action,
                matchedPermission,
                null,
                null);
    }

    public static AccessDecision evaluateTenantWhenScoped(
            OperatorPrincipal operator,
            boolean tenantScoped,
            String tenantId,
            TenantGuard tenantGuard,
            OperatorTenantAccess tenantAccess,
            AccessResource resource,
            AccessAction action,
            String matchedPermission,
            OrgScope orgScope,
            String resourceOrgUnitId) {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(action, "action");
        String subjectId = operator == null ? null : operator.subjectId();
        AccessDecision afterTenant;
        if (!tenantScoped) {
            afterTenant =
                    AccessDecision.allow(subjectId, tenantId, orgScope, resource, action, matchedPermission);
        } else {
            afterTenant = evaluateTenantAfterPermission(
                    operator,
                    subjectId,
                    matchedPermission,
                    tenantId,
                    tenantGuard,
                    tenantAccess,
                    resource,
                    action,
                    orgScope);
        }
        if (!afterTenant.allowed()) {
            return afterTenant;
        }
        return applyOrgScope(afterTenant, orgScope, resourceOrgUnitId);
    }

    /**
     * After permission+tenant allow: attach scope; fail-closed when resource unit present —
     * 权限与租户通过后附带范围；资源单元存在时缺失范围 fail-closed。
     */
    public static AccessDecision applyOrgScope(
            AccessDecision decision, OrgScope orgScope, String resourceOrgUnitId) {
        Objects.requireNonNull(decision, "decision");
        AccessDecision withScope = orgScope == null ? decision : decision.withOrgScope(orgScope);
        if (!withScope.allowed()) {
            return withScope;
        }
        if (resourceOrgUnitId == null || resourceOrgUnitId.isBlank()) {
            return withScope;
        }
        if (orgScope == null) {
            return AccessDecision.deny(
                    withScope.subjectId(),
                    withScope.tenantId(),
                    null,
                    withScope.resource(),
                    withScope.action(),
                    withScope.matchedPermission(),
                    AccessDecision.DENY_ORG_SCOPE_MISSING);
        }
        if (orgScope.isUnrestricted()) {
            return withScope;
        }
        if (orgScope.isNone() || !orgScope.contains(resourceOrgUnitId)) {
            return AccessDecision.deny(
                    withScope.subjectId(),
                    withScope.tenantId(),
                    orgScope,
                    withScope.resource(),
                    withScope.action(),
                    withScope.matchedPermission(),
                    AccessDecision.DENY_ORG_OUT_OF_SCOPE);
        }
        return withScope;
    }

    /**
     * Fail-closed: deny → {@link AccessDecisionDeniedException} — 拒绝则抛带决策的异常。
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
        return require(
                operator,
                permission,
                tenantScoped,
                tenantId,
                tenantGuard,
                tenantAccess,
                resource,
                action,
                null,
                null);
    }

    public static AccessDecision require(
            OperatorPrincipal operator,
            String permission,
            boolean tenantScoped,
            String tenantId,
            TenantGuard tenantGuard,
            OperatorTenantAccess tenantAccess,
            AccessResource resource,
            AccessAction action,
            OrgScope orgScope,
            String resourceOrgUnitId) {
        AccessDecision decision = evaluate(
                operator,
                permission,
                tenantScoped,
                tenantId,
                tenantGuard,
                tenantAccess,
                resource,
                action,
                orgScope,
                resourceOrgUnitId);
        if (!decision.allowed()) {
            throw new AccessDecisionDeniedException(decision, permission);
        }
        return decision;
    }

    /**
     * Fail-closed org-scope check on an already-allowed decision —
     * 在已允许决策上做失败关闭的组织范围核对。
     */
    public static AccessDecision requireOrgScope(
            AccessDecision allowedDecision, OrgScope orgScope, String resourceOrgUnitId, String requiredPermission) {
        AccessDecision decision = applyOrgScope(allowedDecision, orgScope, resourceOrgUnitId);
        if (!decision.allowed()) {
            throw new AccessDecisionDeniedException(
                    decision, requiredPermission == null || requiredPermission.isBlank() ? "permission" : requiredPermission);
        }
        return decision;
    }

    private static AccessDecision evaluateTenantAfterPermission(
            OperatorPrincipal operator,
            String subjectId,
            String matchedPermission,
            String tenantId,
            TenantGuard tenantGuard,
            OperatorTenantAccess tenantAccess,
            AccessResource resource,
            AccessAction action,
            OrgScope orgScope) {
        Objects.requireNonNull(tenantGuard, "tenantGuard");
        Objects.requireNonNull(tenantAccess, "tenantAccess");
        try {
            tenantGuard.requireTenant(tenantId);
        } catch (TenantMissingException ex) {
            return AccessDecision.deny(
                    subjectId,
                    tenantId,
                    orgScope,
                    resource,
                    action,
                    matchedPermission,
                    AccessDecision.DENY_TENANT_MISSING);
        }
        if (operator == null || !tenantAccess.isGranted(operator, tenantId)) {
            return AccessDecision.deny(
                    subjectId,
                    tenantId,
                    orgScope,
                    resource,
                    action,
                    matchedPermission,
                    AccessDecision.DENY_TENANT_NOT_GRANTED);
        }
        return AccessDecision.allow(subjectId, tenantId, orgScope, resource, action, matchedPermission);
    }
}
