package com.subjex.platform.app.security;

import com.subjex.platform.contract.tenant.TenantGuard;

/**
 * DeclarationAccess — 声明访问：按 YAML 上的权限名与租户标志做失败关闭检查。
 * <p>
 * Reuses the operator's granted permission names. Missing or blank permission denies.
 * When {@code tenantScoped} is true, the tenant header must pass {@link TenantGuard}
 * and {@link OperatorTenantAccess} (operator–tenant grant).
 * Denied permission throws {@link DeclarationPermissionDeniedException} so the API can name it.
 * 复用操作员已授予的权限名。权限缺失或空白即拒绝。
 * {@code tenantScoped} 为 true 时，租户头必须通过 {@link TenantGuard} 与 {@link OperatorTenantAccess}。
 * 缺权限抛出 {@link DeclarationPermissionDeniedException}，接口可写出权限名。
 */
public final class DeclarationAccess {

    private DeclarationAccess() {}

    /**
     * Deny unless the operator holds the named permission — 操作员未持有该具名权限则拒绝。
     */
    public static void requirePermission(OperatorPrincipal operator, String permission) {
        if (permission == null || permission.isBlank()) {
            throw new DeclarationPermissionDeniedException("permission");
        }
        if (operator == null || !operator.permissionNames().contains(permission)) {
            throw new DeclarationPermissionDeniedException(permission);
        }
    }

    /**
     * When the declaration is tenant-scoped, require tenant id and operator grant —
     * 声明按租户隔离时要求租户标识且操作员获准代表该租户。
     */
    public static void requireTenantWhenScoped(
            TenantGuard tenantGuard,
            OperatorTenantAccess tenantAccess,
            OperatorPrincipal operator,
            boolean tenantScoped,
            String tenantId) {
        if (tenantScoped) {
            tenantGuard.requireTenant(tenantId);
            tenantAccess.requireGranted(operator, tenantId);
        }
    }
}
