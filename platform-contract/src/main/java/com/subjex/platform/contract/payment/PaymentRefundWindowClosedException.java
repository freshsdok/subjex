package com.subjex.platform.contract.payment;

/**
 * PaymentRefundWindowClosedException — refund refused because more than 7 days have passed since success.
 * 距支付成功已超过 7 天，退款被拒绝。
 * <p>
 * The clock is {@code succeeded_at}. Partial refunds use the same window from the original success.
 * 时钟为 {@code succeeded_at}。部分退款也以首次成功时间为准，不另开窗口。
 */
public final class PaymentRefundWindowClosedException extends RuntimeException {

    /** Stable HTTP reason — 稳定的 HTTP 原因码. */
    public static final String REASON = "payment-refund-window-closed";

    public PaymentRefundWindowClosedException(String tenantId, String paymentId) {
        super("payment refund window closed: tenant " + tenantId + " payment " + paymentId);
    }
}
