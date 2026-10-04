package com.subjex.platform.contract.tenant;

/**
 * DenyWhenTenantMissing — 租户缺失时拒绝：{@link TenantGuard} 的默认实现。
 * <p>
 * Null and blank are the same fact: the caller did not name a tenant.
 * null 与空白是同一事实：调用方没有指明租户。
 */
public final class DenyWhenTenantMissing implements TenantGuard {

    @Override
    public void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantMissingException();
        }
    }
}
