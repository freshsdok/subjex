package com.subjex.platform.contract.inbox;

import java.util.List;
import java.util.Objects;

/**
 * InboxSendResult — 发送结果：消息 + 收件行（同步时）+ 附件 + 写扩散状态.
 *
 * @param message canonical message — 规范消息
 * @param entries fan-out entries already written (empty when {@link InboxFanoutStatus#PENDING}) — 已写收件行
 * @param attachments bound attachments (may be empty) — 已绑定附件
 * @param fanoutStatus COMPLETE or PENDING — 写扩散状态
 * @param fanoutJobId async job id when PENDING; null when COMPLETE — 异步任务 id
 */
public record InboxSendResult(
        InboxMessage message,
        List<InboxEntry> entries,
        List<InboxAttachment> attachments,
        InboxFanoutStatus fanoutStatus,
        String fanoutJobId) {

    public InboxSendResult {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(attachments, "attachments");
        Objects.requireNonNull(fanoutStatus, "fanoutStatus");
        entries = List.copyOf(entries);
        attachments = List.copyOf(attachments);
        if (fanoutStatus == InboxFanoutStatus.PENDING) {
            Objects.requireNonNull(fanoutJobId, "fanoutJobId");
        }
    }

    /** Sync complete helper — 同步完成便捷构造. */
    public InboxSendResult(InboxMessage message, List<InboxEntry> entries, List<InboxAttachment> attachments) {
        this(message, entries, attachments, InboxFanoutStatus.COMPLETE, null);
    }

    /** Back-compat two-arg (no attachments) — 兼容无附件构造. */
    public InboxSendResult(InboxMessage message, List<InboxEntry> entries) {
        this(message, entries, List.of(), InboxFanoutStatus.COMPLETE, null);
    }
}
