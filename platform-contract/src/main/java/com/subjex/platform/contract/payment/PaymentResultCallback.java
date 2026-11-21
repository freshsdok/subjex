package com.subjex.platform.contract.payment;

/**
 * PaymentResultCallback — 支付结果回调命令：按厂商通知标识幂等入账。
 * <p>
 * {@code providerNotificationId} is the logical event key stored on the payment aggregate.
 * A replay with the same id and the same payload must return the already-applied document.
 * A mismatch on that id fails closed. {@code tenantId} may be blank so the port can fail closed.
 * {@code providerNotificationId} 是落在支付聚合上的逻辑事件键。同一标识且同一载荷的回放必须返回已入账单据。
 * 标识相同但载荷冲突则失败关闭。允许 {@code tenantId} 为空以便端口失败关闭。
 */
public record PaymentResultCallback(
        String tenantId,
        String paymentId,
        String providerNotificationId,
        PaymentStatus resultingStatus,
        String providerReference) {

    public PaymentResultCallback {
        if (tenantId != null) {
            tenantId = tenantId.trim();
        }
        if (paymentId != null) {
            paymentId = paymentId.trim();
        }
        if (providerNotificationId != null) {
            providerNotificationId = providerNotificationId.trim();
        }
        if (providerReference != null && providerReference.isBlank()) {
            providerReference = null;
        } else if (providerReference != null) {
            providerReference = providerReference.trim();
        }
    }
}
