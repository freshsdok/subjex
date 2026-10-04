package com.subjex.platform.contract.task;

/**
 * OutboxState — 出箱状态：这条待发出的事件还在箱里、已经送到另一进程，还是已进死信。
 */
public enum OutboxState {
    PENDING,
    PUBLISHED,
    DEAD
}
