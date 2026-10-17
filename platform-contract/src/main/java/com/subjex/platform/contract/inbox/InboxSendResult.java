package com.subjex.platform.contract.inbox;

import java.util.List;
import java.util.Objects;

/**
 * InboxSendResult — 发送结果：落库的消息与收件行。
 *
 * @param message stored message — 已存消息
 * @param entries stored entries (same order as request recipients after dedupe by realm+subject) — 已存收件行
 */
public record InboxSendResult(InboxMessage message, List<InboxEntry> entries) {

    public InboxSendResult {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(entries, "entries");
        entries = List.copyOf(entries);
    }
}
