package com.subjex.platform.contract.task;

/**
 * TaskState — 任务状态：这条任务现在走到哪一步。
 * <p>
 * {@link #AWAITING_CONFIRMATION} is not {@link #COMPLETED}. A dead task is no longer retried.
 * {@link #AWAITING_CONFIRMATION} 不是 {@link #COMPLETED}。进入死信的任务不再重试。
 */
public enum TaskState {
    PENDING,
    AWAITING_CONFIRMATION,
    COMPLETED,
    DEAD
}
