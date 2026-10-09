package com.subjex.platform.app.tenant;

/**
 * TenantQuotaExceeded — 租户配额硬顶超限（日提交数或存储行数）。
 */
public final class TenantQuotaExceeded extends RuntimeException {

    private final String reason;

    public TenantQuotaExceeded(String reason) {
        super(reason);
        this.reason = reason == null ? "tenant-quota" : reason;
    }

    public String reason() {
        return reason;
    }
}
