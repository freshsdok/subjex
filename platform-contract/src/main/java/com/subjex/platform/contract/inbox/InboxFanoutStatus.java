package com.subjex.platform.contract.inbox;

/**
 * InboxFanoutStatus — 写扩散状态：同步完成 / 异步排队中.
 */
public enum InboxFanoutStatus {

    /** All entries written in the send transaction — 发送事务内已写完全部收件行. */
    COMPLETE,

    /** Message saved; entries still being written by fan-out job — 消息已落库；收件行由异步任务继续写. */
    PENDING
}
