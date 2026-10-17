package com.subjex.platform.contract.inbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * InboxPort — 站内信端口：同步写扩散 + 本人收件箱读写（JDBC 真相源）。
 * <p>
 * Every list/get/mark-read requires {@code tenantId} + realm + subject (fail-closed).
 * Cross-tenant SYSTEM = caller sends once per target tenant (ADR 0008 locked decision 1).
 * 每次列表/详情/已读都要租户 + 领域 + 主体（失败关闭）。跨租户 SYSTEM = 按目标租户各发一次。
 */
public interface InboxPort {

    /**
     * Insert one message and fan-out entries in one transaction — 同事务插入一条消息并写扩散收件行.
     */
    InboxSendResult send(InboxSendRequest request);

    /**
     * List own entries newest-first — 列出本人收件，最新在前.
     */
    List<InboxEntryView> listEntries(
            String tenantId,
            InboxRecipientRealm realm,
            String recipientSubjectId,
            InboxListQuery query);

    /**
     * Count unread for badge — 未读条数（角标）.
     */
    long unreadCount(String tenantId, InboxRecipientRealm realm, String recipientSubjectId);

    /**
     * Mark one entry read (idempotent). Returns false if missing / wrong owner-tenant.
     * 标记一条已读（幂等）。找不到或租户/收件人不匹配时返回 false。
     */
    boolean markRead(
            String tenantId,
            InboxRecipientRealm realm,
            String recipientSubjectId,
            String entryId,
            Instant readAt);

    /**
     * Own entry detail or empty — 本人详情，否则 empty.
     */
    Optional<InboxEntryView> getEntry(
            String tenantId, InboxRecipientRealm realm, String recipientSubjectId, String entryId);

    /**
     * Entry+message by id within tenant (no subject filter) — for AuthZ (404 vs 403).
     * 租户内按 id 取详情（不过滤收件人）— 供鉴权区分 404 / 403。
     */
    Optional<InboxEntryView> findEntryInTenant(String tenantId, String entryId);
}
