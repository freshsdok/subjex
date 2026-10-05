package com.subjex.platform.app.security;

import java.util.List;

/**
 * OperatorTenantAccess — 操作员—租户授权：租户作用域动作前必须确认操作员获准代表该租户。
 * <p>
 * Fail-closed: missing grant denies. {@link #ALL_TENANTS} grants every tenant id.
 * 失败关闭：无授权即拒绝。{@link #ALL_TENANTS} 表示全部租户。
 */
public interface OperatorTenantAccess {

    /** Wildcard tenant id meaning every tenant — 通配：全部租户。 */
    String ALL_TENANTS = "*";

    /** Whether the operator may act for this tenant id — 操作员是否可代表该租户行动。 */
    boolean isGranted(OperatorPrincipal operator, String tenantId);

    /**
     * Deny unless {@link #isGranted} — 未授权则拒绝。
     *
     * @throws OperatorTenantNotGrantedException when the operator lacks the grant
     */
    void requireGranted(OperatorPrincipal operator, String tenantId);

    /** Tenant ids granted to the subject (may include {@link #ALL_TENANTS}) — 主体已获授权的租户标识。 */
    List<String> listGrants(String subjectId);

    /** Replace all grants for the subject — 替换该主体的全部租户授权。 */
    void replaceGrants(String subjectId, List<String> tenantIds);

    /** Ensure a single grant row exists (idempotent) — 确保存在一行授权（幂等）。 */
    void ensureGrant(String subjectId, String tenantId);
}
