package com.subjex.platform.contract.payment;

/**
 * PaymentStatusChanged — 写入现有 {@code outbox_event} 的支付状态变更通知（不是任务通知，也不是域文案）。
 * <p>
 * Wire name is {@link #EVENT_NAME}. The body carries payment id, tenant, status, amounts, and
 * provider reference. This is a consumer notice in the same role as {@code TaskRecorded} and is
 * delivered via the existing {@code DeliveryPort} path. It must not be produced via
 * {@code TaskMessagePort.submit}, and must not be parsed as {@code TaskRecordedNotice}.
 * 线路名为 {@link #EVENT_NAME}。事件体携带支付单标识、租户、状态、金额与厂商引用。
 * 这是与 {@code TaskRecorded} 同类的消费通知，经现有 {@code DeliveryPort} 投递。
 * 不得经 {@code TaskMessagePort.submit} 产生，也不得当成 {@code TaskRecordedNotice} 解析。
 */
public record PaymentStatusChanged(
        String eventId,
        String tenantId,
        String paymentId,
        PaymentStatus status,
        long amountMinor,
        long refundedAmountMinor,
        String currency,
        String providerId,
        String providerReference) {

    /** Wire name stored in {@code outbox_event.event_name} — 写入 {@code outbox_event.event_name} 的线路名. */
    public static final String EVENT_NAME = "PaymentStatusChanged";
}
