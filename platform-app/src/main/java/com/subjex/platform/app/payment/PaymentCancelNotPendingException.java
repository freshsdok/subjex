package com.subjex.platform.app.payment;

/**
 * PaymentCancelNotPendingException — cancel is refused because the document is no longer PENDING.
 * 单据已不是待支付，不能取消。
 */
public final class PaymentCancelNotPendingException extends RuntimeException {

    /** Stable HTTP reason — 稳定的 HTTP 原因码. */
    public static final String REASON = "payment-cancel-not-pending";

    public PaymentCancelNotPendingException(String tenantId, String paymentId) {
        super("payment " + paymentId + " in tenant " + tenantId + " is not pending and cannot be cancelled");
    }
}
