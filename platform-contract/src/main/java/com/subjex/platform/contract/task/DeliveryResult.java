package com.subjex.platform.contract.task;

/**
 * DeliveryResult — 投递结果：这一次把出箱事件送往另一进程之后的状态。
 * <p>
 * {@code failureReason} is empty when the event was published.
 * 事件已送出时 {@code failureReason} 为空。
 */
public record DeliveryResult(OutboxState eventState, String failureReason) {

    public static DeliveryResult published() {
        return new DeliveryResult(OutboxState.PUBLISHED, null);
    }

    public static DeliveryResult pending(String failureReason) {
        return new DeliveryResult(OutboxState.PENDING, failureReason);
    }
}
