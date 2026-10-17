package com.subjex.platform.contract.inbox;

import java.time.Instant;
import java.util.Objects;

/**
 * InboxEntry — 收件箱行：每人一条 + 已读状态。
 *
 * @param entryId primary key — 主键
 * @param messageId parent message — 所属消息
 * @param tenantId denormalized tenant — 冗余租户（隔离查询）
 * @param recipientRealm OPERATOR / END_USER — 收件人领域
 * @param recipientSubjectId recipient subject — 收件人主体
 * @param readAt null = unread — null 表示未读
 * @param createdAt created instant — 创建时间
 */
public record InboxEntry(
        String entryId,
        String messageId,
        String tenantId,
        InboxRecipientRealm recipientRealm,
        String recipientSubjectId,
        Instant readAt,
        Instant createdAt) {

    public InboxEntry {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(recipientRealm, "recipientRealm");
        Objects.requireNonNull(recipientSubjectId, "recipientSubjectId");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Whether this entry is still unread — 是否仍未读. */
    public boolean unread() {
        return readAt == null;
    }
}
