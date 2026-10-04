package com.subjex.platform.contract.task;

/**
 * DeliveryProgress — 投递进展：一次跨进程发送之后，出箱行应变成哪种状态。
 * <p>
 * Published wins. Otherwise the attempt count decides between waiting and dead.
 * 送出成功优先。否则由尝试次数决定继续等待还是进入死信。
 */
public final class DeliveryProgress {

    private DeliveryProgress() {
    }

    public static OutboxState next(boolean delivered, int attemptCount, int maxAttempt) {
        if (delivered) {
            return OutboxState.PUBLISHED;
        }
        if (attemptCount >= maxAttempt) {
            return OutboxState.DEAD;
        }
        return OutboxState.PENDING;
    }
}
