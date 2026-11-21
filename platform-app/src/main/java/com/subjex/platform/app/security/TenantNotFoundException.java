package com.subjex.platform.app.security;

/**
 * TenantNotFoundException — 租户不存在：任务写入时不得自动建户，失败关闭。
 * <p>
 * The tenant id was present but has no row. When auto-create-on-submit is off, refuse the write
 * instead of inserting an {@code ACTIVE} tenant as a side effect.
 * 请求已带租户标识但库中无行。关闭首次提交自动建户时，拒绝写入，而不是顺便插入 {@code ACTIVE} 租户。
 */
public final class TenantNotFoundException extends RuntimeException {

    public static final String REASON = "tenant-not-found";

    public TenantNotFoundException(String tenantId) {
        super("tenant not found: " + tenantId);
    }

    public String reason() {
        return REASON;
    }
}
