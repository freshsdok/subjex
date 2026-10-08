package com.subjex.platform.app.declaration;

import com.subjex.platform.app.security.TenantEnforcementFilter;

/**
 * TenantDeclarationContext — 租户声明上下文：何时对 classpath 做库内草稿覆盖。
 * <p>
 * Runtime overlay (entity / form / flow) applies only when the request carries a non-blank
 * {@link TenantEnforcementFilter#TENANT_HEADER} ({@code X-Tenant-Id}) and the operator is
 * granted that tenant ({@code OperatorTenantAccess.requireGranted}). Classpath catalogs remain
 * the baseline when the header is absent.
 * 仅当请求带非空 {@code X-Tenant-Id} 且操作员已获该租户授权时，运行时才用库内草稿覆盖
 * classpath；无租户头时仍走 classpath 基线。
 */
public final class TenantDeclarationContext {

    private TenantDeclarationContext() {}

    /**
     * Whether the caller asked for tenant-scoped declaration overlay — 调用方是否请求租户声明覆盖。
     */
    public static boolean overlayRequested(String tenantHeader) {
        return tenantHeader != null && !tenantHeader.isBlank();
    }

    /**
     * Trimmed tenant id when overlay is requested; otherwise {@code null} — 请求覆盖时返回修剪后的租户 id，否则 null。
     */
    public static String tenantIdOrNull(String tenantHeader) {
        return overlayRequested(tenantHeader) ? tenantHeader.trim() : null;
    }

    /** Header name used for overlay — 覆盖所用请求头名。 */
    public static String tenantHeaderName() {
        return TenantEnforcementFilter.TENANT_HEADER;
    }
}
