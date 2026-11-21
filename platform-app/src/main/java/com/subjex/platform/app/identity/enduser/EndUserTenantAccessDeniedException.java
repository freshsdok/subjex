package com.subjex.platform.app.identity.enduser;

/**
 * EndUserTenantAccessDeniedException — no ACTIVE identity in the requested business tenant.
 * 在请求的业务租户中没有 ACTIVE 身份（失败关闭）。
 */
public final class EndUserTenantAccessDeniedException extends RuntimeException {

    /** Stable HTTP reason — 稳定拒绝原因. */
    public static final String REASON = "end-user-tenant-access-denied";

    public EndUserTenantAccessDeniedException() {
        super(REASON);
    }
}
