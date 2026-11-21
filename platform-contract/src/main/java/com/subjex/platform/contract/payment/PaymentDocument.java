package com.subjex.platform.contract.payment;

import java.time.Instant;
import java.util.List;

/**
 * PaymentDocument — 支付单据：查单的真相源，含状态、金额、退款行与厂商引用。
 * <p>
 * {@code refunds} is empty until WP6-2 applies a refund. {@code refundedAmount} is the same
 * currency as {@code amount} and starts at zero.
 * 退款行在 WP6-2 入账前为空。{@code refundedAmount} 与原单同币种，创单时为零。
 */
public record PaymentDocument(
        String paymentId,
        String tenantId,
        PaymentAmount amount,
        PaymentAmount refundedAmount,
        PaymentStatus status,
        String reference,
        String payerSubjectId,
        String payerRealm,
        String providerId,
        String providerReference,
        List<PaymentRefundLine> refunds,
        Instant createdAt,
        Instant updatedAt) {

    public PaymentDocument {
        refunds = refunds == null ? List.of() : List.copyOf(refunds);
    }
}
