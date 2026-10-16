package com.subjex.platform.contract.task;

/**
 * TaskMessagePort — 任务与消息的唯一端口。
 * <p>
 * Submitting a task, retrying a deterministic step, confirming a person, recording cross-process delivery,
 * and relaying PENDING outbox rows all enter here. There is not a second messaging port beside this one.
 * 提交任务、重试确定性步骤、记下人工确认、记录跨进程投递、重投 PENDING 出箱行，都从这里进入。
 * 旁边没有第二个消息端口。
 */
public interface TaskMessagePort {

    /**
     * Persist the task and the outbox row, then push that row through the selected DeliveryPort.
     * 写下任务和出箱行，再经所选 DeliveryPort 推出去。
     */
    TaskRecord submit(TaskCommand command);

    /**
     * Retry one deterministic task that is still pending — 重试一条仍在等待的确定性任务。
     */
    TaskRecord retry(String tenantId, String taskId, boolean stepSucceeded);

    /**
     * Record human confirmation. Unconfirmed stays incomplete — 记下人工确认。未确认则保持未完成。
     */
    TaskRecord confirm(String tenantId, String taskId);

    /**
     * Push a batch of PENDING outbox rows (background relay). Returns how many became PUBLISHED.
     * Skips rows when the circuit breaker refuses the call so attempt counts are not burned while open.
     * 后台重投一批 PENDING 出箱行；返回变成 PUBLISHED 的条数。熔断拒呼时跳过，以免冷却期内耗尽尝试次数。
     */
    int relayPending(int batchSize);

    /**
     * Replay an OUTBOX_EVENT dead letter: reset event to PENDING and remove the letter, then deliver once.
     * TASK-origin letters are not supported here (use task retry).
     * 重放出箱死信：事件重回 PENDING 并删死信行，再投递一次。任务来源不支持（用任务重试）。
     */
    OutboxEvent replayDeadLetter(String deadLetterId);

    /**
     * Discard a dead letter (mark discarded_at). Idempotent if already discarded.
     * 丢弃死信（写 discarded_at）；已丢弃则幂等。
     */
    DeadLetter discardDeadLetter(String deadLetterId);
}
