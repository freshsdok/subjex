package com.subjex.platform.app.security;

/**
 * TenantDisabledException — 租户已禁用：业务租户处于 SUSPENDED，拒绝新的任务写入。
 */
public final class TenantDisabledException extends RuntimeException {

    public TenantDisabledException(String tenantId) {
        super("tenant is disabled: " + tenantId);
    }
}
