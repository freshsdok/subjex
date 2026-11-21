package com.subjex.platform.contract.consent;

/**
 * ConsentCrossTenantDeniedException — the subject has no ACTIVE identity in the requested tenant.
 * 主体在所请求租户内没有 ACTIVE 身份（禁止跨租户读写同意记录）。
 */
public final class ConsentCrossTenantDeniedException extends RuntimeException {

    /** Stable reason — 稳定原因. */
    public static final String REASON = "consent-cross-tenant-denied";

    public ConsentCrossTenantDeniedException() {
        super(REASON);
    }
}
