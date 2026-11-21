package com.subjex.platform.contract.notification;

import com.subjex.platform.contract.task.OutboxEvent;

/**
 * NotificationDispatcher — 通知调度器：读取 PENDING 的 {@code NotificationRequested}，调用渠道适配器，并复用出箱重试/死信。
 * <p>
 * This is not {@code OutboxRelay} / {@code DeliveryPort}. Those remain the TaskRecorded socket/Kafka path.
 * Dispatch happens in-process after the outbox row is durable.
 * 这不是 {@code OutboxRelay} / {@code DeliveryPort}（那条路仍只服务 TaskRecorded）。出箱行落库后在进程内调度适配器。
 */
public interface NotificationDispatcher {

    /**
     * Dispatch a batch of PENDING {@code NotificationRequested} rows. Returns how many became PUBLISHED.
     * 调度一批 PENDING 的 {@code NotificationRequested}；返回变成 PUBLISHED 的条数。
     */
    int dispatchPending(int batchSize);

    /**
     * Dispatch one PENDING {@code NotificationRequested} row (for example after a dead-letter restore).
     * 调度一条 PENDING 的 {@code NotificationRequested}（例如死信恢复之后）。
     */
    OutboxEvent dispatchOne(String eventId);

    /**
     * Replay an {@code OUTBOX_EVENT} dead letter whose origin is {@code NotificationRequested}:
     * reset the row to PENDING, delete the letter, then dispatch once via the channel adapter.
     * 重放 {@code NotificationRequested} 出箱死信：行重回 PENDING 并删死信，再经渠道适配器投递一次。
     */
    OutboxEvent replayDeadLetter(String deadLetterId);
}
