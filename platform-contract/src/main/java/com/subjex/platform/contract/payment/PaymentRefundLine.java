package com.subjex.platform.contract.payment;

import java.time.Instant;

/**
 * PaymentRefundLine — 支付单上的一条退款记录；本切片创单后为空，入账在 WP6-2。
 * <p>
 * Refund apply is unsupported in WP6-1. The type exists so {@link PaymentDocument} can already
 * expose refund lines as the query source of truth.
 * 本切片不办理退款。此类型已放在 {@link PaymentDocument} 上，以便查单作为真相源。
 */
public record PaymentRefundLine(
        String refundId,
        PaymentAmount amount,
        String refundStatus,
        String providerRefundId,
        Instant createdAt) {}
