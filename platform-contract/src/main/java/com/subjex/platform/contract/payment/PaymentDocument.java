package com.subjex.platform.contract.payment;

import java.time.Instant;
import java.util.List;

/**
 * PaymentDocument — 支付单据：查单的真相源，含状态、金额、退款行、厂商引用与生命周期时间。
 * <p>
 * {@code refunds} is empty until a refund is applied. {@code refundedAmount} is the same
 * currency as {@code amount} and starts at zero. {@code expiresAt} is {@code created_at} plus
 * 30 minutes for a pending document. {@code succeededAt} is when the payment first became
 * {@code SUCCEEDED} and starts the 7-day refund window. {@code cancelledAt} is set when the
 * payer cancels a pending document.
 * 退款入账前退款行为空。{@code refundedAmount} 与原单同币种，创单时为零。
 * 待支付的 {@code expiresAt} 为 {@code created_at} 加 30 分钟。{@code succeededAt} 是首次成功入账时间，
 * 也是 7 日退款窗口的起点。{@code cancelledAt} 在付款人取消待支付单据时写入。
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
        Instant updatedAt,
        Instant expiresAt,
        Instant succeededAt,
        Instant cancelledAt) {

    public PaymentDocument {
        refunds = refunds == null ? List.of() : List.copyOf(refunds);
    }
}
