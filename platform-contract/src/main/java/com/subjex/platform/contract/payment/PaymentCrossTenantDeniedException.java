package com.subjex.platform.contract.payment;

/**
 * PaymentCrossTenantDeniedException — 跨租户查单被拒绝：租户 A 不得读取租户 B 的支付单。
 * <p>
 * No additional voucher or payment row is written when this is thrown.
 * 抛出时不得再写凭证或支付行。
 */
public final class PaymentCrossTenantDeniedException extends RuntimeException {

    public PaymentCrossTenantDeniedException(String requestTenantId, String paymentTenantId) {
        super("cross-tenant payment query denied: tenant "
                + requestTenantId
                + " cannot read a payment belonging to "
                + paymentTenantId);
    }
}
