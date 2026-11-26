package com.subjex.platform.contract.payment;

/**
 * PaymentCancelDeniedException — the caller is not the payer, so cancel is refused.
 * 调用方不是付款人，取消被拒绝。
 */
public final class PaymentCancelDeniedException extends RuntimeException {

    /** Stable HTTP reason — 稳定的 HTTP 原因码. */
    public static final String REASON = "payment-cancel-denied";

    public PaymentCancelDeniedException(String tenantId, String paymentId) {
        super("payment cancel denied: tenant " + tenantId + " payment " + paymentId);
    }
}
