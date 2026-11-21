package com.subjex.platform.contract.exporthook;

/**
 * ExportCrossTenantDeniedException — subject or request is not in the requested business tenant.
 * 主体或申请不在所请求的业务租户内。
 */
public final class ExportCrossTenantDeniedException extends RuntimeException {

    /** Stable reason — 稳定原因. */
    public static final String REASON = "export-cross-tenant-denied";

    public ExportCrossTenantDeniedException() {
        super(REASON);
    }
}
