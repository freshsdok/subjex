package com.subjex.platform.app.payment;

/**
 * PaymentChargeNotPendingException — start-charge is refused because the document is no longer PENDING.
 * 单据已不是待支付，不能发起扣款。
 */
public final class PaymentChargeNotPendingException extends RuntimeException {

    /** Stable HTTP reason — 稳定的 HTTP 原因码. */
    public static final String REASON = "payment-charge-not-pending";

    public PaymentChargeNotPendingException(String tenantId, String paymentId) {
        super("payment " + paymentId + " in tenant " + tenantId + " is not pending and cannot start charge");
    }
}
