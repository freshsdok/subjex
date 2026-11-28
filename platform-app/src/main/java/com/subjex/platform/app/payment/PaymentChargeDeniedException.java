package com.subjex.platform.app.payment;

/**
 * PaymentChargeDeniedException — only the payer may start charge on their own document.
 * 仅付款人可以对本人单据发起扣款。
 */
public final class PaymentChargeDeniedException extends RuntimeException {

    /** Stable HTTP reason — 稳定的 HTTP 原因码. */
    public static final String REASON = "payment-charge-denied";

    public PaymentChargeDeniedException(String tenantId, String paymentId) {
        super("payment " + paymentId + " in tenant " + tenantId + " cannot be charged by this payer");
    }
}
