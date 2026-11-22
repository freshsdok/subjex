package com.subjex.platform.app.tenant;

/**
 * TenantHostClosedException — bound host points at a missing or disabled tenant; fail closed.
 * 已绑定主机指向缺失或已停用的租户；失败关闭。
 */
public final class TenantHostClosedException extends RuntimeException {
    public TenantHostClosedException(String message) {
        super(message);
    }
}
