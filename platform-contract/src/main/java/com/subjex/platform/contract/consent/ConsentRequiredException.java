package com.subjex.platform.contract.consent;

/**
 * ConsentRequiredException — fail-closed when {@code ConsentPort.require} is not satisfied.
 * {@code require} 未满足时的失败关闭异常（缺失、已撤销或版本不匹配都用这一类型）。
 * <p>
 * One type covers a missing grant, a withdrawn grant, and a version mismatch.
 * 缺失授予、已撤销授予与版本不匹配共用这一类型。
 */
public final class ConsentRequiredException extends RuntimeException {

    /** Stable reason for HTTP / callers — 给 HTTP 与调用方的稳定原因. */
    public static final String REASON = "consent-required";

    public ConsentRequiredException() {
        super(REASON);
    }
}
