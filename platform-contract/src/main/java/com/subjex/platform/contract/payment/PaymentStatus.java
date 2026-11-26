package com.subjex.platform.contract.payment;

/**
 * PaymentStatus — 支付单据状态。
 * <p>
 * {@link #PENDING} is the payable document after create. Unpaid {@link #PENDING} orders
 * expire 30 minutes after {@code created_at} ({@link #EXPIRED}). The payer may cancel a
 * pending document ({@link #CANCELLED}). {@link #SUCCEEDED}, {@link #PARTIALLY_REFUNDED},
 * {@link #REFUNDED}, and {@link #FAILED} are applied by the payment-result and refund paths.
 * Refunds are only accepted within 7 days of {@code succeeded_at}.
 * 创单后为待支付（{@link #PENDING}）。未支付单据自 {@code created_at} 起 30 分钟过期（{@link #EXPIRED}）。
 * 付款人可取消待支付单据（{@link #CANCELLED}）。成功、部分退款、已退款与失败由支付结果与退款路径入账。
 * 退款仅在 {@code succeeded_at} 起 7 日内受理。
 */
public enum PaymentStatus {
    /** Payable document after create / 创单后的待支付单据 */
    PENDING,
    /** Payment captured successfully / 支付已成功入账 */
    SUCCEEDED,
    /** Provider or mock reported failure / 厂商或模拟适配器报告失败 */
    FAILED,
    /** At least one partial refund applied / 已发生至少一笔部分退款 */
    PARTIALLY_REFUNDED,
    /** Fully refunded / 已全额退款 */
    REFUNDED,
    /** Pending payment expired after 30 minutes / 待支付单据已过 30 分钟未付而过期 */
    EXPIRED,
    /** Pending payment cancelled by the payer / 待支付单据已被付款人取消 */
    CANCELLED
}
