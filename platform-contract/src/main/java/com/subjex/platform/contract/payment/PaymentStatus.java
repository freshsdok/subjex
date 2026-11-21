package com.subjex.platform.contract.payment;

/**
 * PaymentStatus — 支付单据状态：创单为待支付；成功与退款状态留给后续切片入账。
 * <p>
 * {@link #PENDING} is the payable document after create. {@link #SUCCEEDED},
 * {@link #PARTIALLY_REFUNDED}, {@link #REFUNDED}, and {@link #FAILED} are applied in WP6-2.
 * 创单后为待支付（{@link #PENDING}）。成功、部分退款、已退款与失败由 WP6-2 入账。
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
    REFUNDED
}
