package com.subjex.platform.app.tenant;

/**
 * TenantHostAlreadyBoundException — hostname is already bound to a tenant.
 * 该主机名已经绑定到一个租户。
 */
public final class TenantHostAlreadyBoundException extends RuntimeException {
    public TenantHostAlreadyBoundException(String message) {
        super(message);
    }
}
