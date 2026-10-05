package com.subjex.platform.app.security;

/**
 * OperatorTenantNotGrantedException — 操作员未获该租户授权：失败关闭，不猜测其它租户。
 */
public final class OperatorTenantNotGrantedException extends RuntimeException {

    public OperatorTenantNotGrantedException(String tenantId) {
        super("operator is not granted tenant: " + tenantId);
    }
}
