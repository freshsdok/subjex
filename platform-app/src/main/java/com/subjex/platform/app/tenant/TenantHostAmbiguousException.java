package com.subjex.platform.app.tenant;

/**
 * TenantHostAmbiguousException — more than one Host, or Host conflicts with X-Tenant-Id.
 * 主机名不明确：多个 Host，或与 X-Tenant-Id 不一致。
 */
public final class TenantHostAmbiguousException extends RuntimeException {
    public TenantHostAmbiguousException(String message) {
        super(message);
    }
}
