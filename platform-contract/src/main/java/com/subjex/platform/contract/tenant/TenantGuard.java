package com.subjex.platform.contract.tenant;

/**
 * TenantGuard — 租户门禁：租户作用域的动作开始前必须先过这一关。
 * <p>
 * The default implementation denies a missing tenant. Do not replace it with a default-tenant fallback.
 * 默认实现在租户缺失时拒绝。不要改成落回某个默认租户。
 */
public interface TenantGuard {

    /**
     * @param tenantId raw tenant id from the caller; blank counts as missing
     *                 调用方给出的租户标识；空白也算缺失
     */
    void requireTenant(String tenantId);
}
