package com.subjex.platform.contract.tenant;

/**
 * TenantMissingException — 租户缺失：调用方没有给出租户，门禁因此拒绝。
 * <p>
 * This is the fail-closed signal. It is not a prompt to guess a tenant.
 * 这是失败即关闭的信号，不是让系统去猜一个租户。
 */
public final class TenantMissingException extends RuntimeException {

    public TenantMissingException() {
        super("tenant is missing");
    }
}
