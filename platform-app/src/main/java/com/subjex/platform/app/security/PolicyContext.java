package com.subjex.platform.app.security;

/**
 * PolicyContext — 策略引擎上下文（Cedar context；Casbin domain/tenant 子集）。
 * <p>
 * {@code tenantId} + whether the check is tenant-scoped + optional {@link OrgScope}.
 * Null orgScope = unspecified (fail-closed when the resource carries an org id — AUTH-02).
 * Use {@link OrgScope#unrestricted()} for an explicit platform/break-glass grant (AUTH-03).
 * 租户标识、是否租户隔离、可选组织范围。orgScope 为 null 表示未指定；UNRESTRICTED 须显式。
 */
public record PolicyContext(String tenantId, boolean tenantScoped, OrgScope orgScope) {

    public PolicyContext {
        tenantId = tenantId == null || tenantId.isBlank() ? null : tenantId.trim();
    }

    /** No tenant gate, no org filter — 无租户门禁、无组织过滤。 */
    public static PolicyContext unscoped() {
        return new PolicyContext(null, false, null);
    }

    public static PolicyContext of(String tenantId, boolean tenantScoped, OrgScope orgScope) {
        return new PolicyContext(tenantId, tenantScoped, orgScope);
    }
}
