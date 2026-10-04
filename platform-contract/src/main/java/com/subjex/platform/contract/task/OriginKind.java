package com.subjex.platform.contract.task;

/**
 * OriginKind — 死信来源：这条死信来自任务本身，还是来自出箱事件。
 */
public enum OriginKind {
    TASK,
    OUTBOX_EVENT
}
