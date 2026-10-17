package com.subjex.platform.contract.inbox;

import java.util.Objects;

/**
 * InboxEntryView — 列表/详情视图：entry + message 联接结果。
 *
 * @param entry mailbox row — 收件行
 * @param message canonical body — 规范正文
 */
public record InboxEntryView(InboxEntry entry, InboxMessage message) {

    public InboxEntryView {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(message, "message");
        if (!entry.messageId().equals(message.messageId())) {
            throw new IllegalArgumentException("entry.messageId must match message.messageId");
        }
        if (!entry.tenantId().equals(message.tenantId())) {
            throw new IllegalArgumentException("entry.tenantId must match message.tenantId");
        }
    }
}
