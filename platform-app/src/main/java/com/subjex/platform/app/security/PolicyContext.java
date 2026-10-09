package com.subjex.platform.app.security;

/**
 * PolicyContext — 策略引擎上下文（Cedar context；Casbin domain/tenant 子集）。
 * <p>
 * {@code tenantId} + whether the check is tenant-scoped + optional {@link OrganizationScope}.
 * Null orgScope = unspecified (fail-closed when the resource carries an organization id — AUTH-02).
 * Use {@link OrganizationScope#unrestricted()} for an explicit platform/break-glass grant (AUTH-03).
 * Legacy org.legacy.OrgScope callers convert via {@code toOrganizationScope()} before PolicyContext.
 * 租户标识、是否租户隔离、可选组织范围。orgScope 为 null 表示未指定；UNRESTRICTED 须显式。
 */
public record PolicyContext(String tenantId, boolean tenantScoped, OrganizationScope orgScope) {

    public PolicyContext {
        tenantId = tenantId == null || tenantId.isBlank() ? null : tenantId.trim();
    }

    /** No tenant gate, no org filter — 无租户门禁、无组织过滤。 */
    public static PolicyContext unscoped() {
        return new PolicyContext(null, false, null);
    }

    public static PolicyContext of(String tenantId, boolean tenantScoped, OrganizationScope orgScope) {
        return new PolicyContext(tenantId, tenantScoped, orgScope);
    }
}
