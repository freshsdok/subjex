package com.subjex.platform.contract.task;

/**
 * TaskMessagePort — 任务与消息的唯一端口。
 * <p>
 * Submitting a task, retrying a deterministic step, confirming a person, and recording cross-process delivery all enter here.
 * There is not a second messaging port beside this one.
 * 提交任务、重试确定性步骤、记下人工确认、记录跨进程投递，都从这里进入。
 * 旁边没有第二个消息端口。
 */
public interface TaskMessagePort {

    /**
     * Persist the task and the outbox row, then hand the notice to the cross-process stand-in.
     * 写下任务和出箱行，再把通知交给跨进程替身。
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
}
