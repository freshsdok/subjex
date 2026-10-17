package com.subjex.platform.contract.inbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * InboxPort — 站内信端口：同步/异步写扩散 + 本人收件箱读写 + 本人附件（JDBC 真相源）。
 * <p>
 * Every list/get/mark-read/attachment requires {@code tenantId} + realm + subject (fail-closed).
 * Sync fan-out ({@link #send}) rejects unique recipients &gt; {@link InboxSendRequest#SYNC_FANOUT_MAX}.
 * Deferred fan-out ({@link #sendDeferred}) accepts {@code SYNC < N ≤ HARD}; entries land via
 * {@link #processFanoutBatch} (ADR 0009 locked #3). Attachment download = own entry only (locked #5).
 * 每次列表/详情/已读/附件都要租户 + 领域 + 主体（失败关闭）。
 * 同步写扩散超同步上限拒绝；异步路径写消息后由批次写入 inbox_entry。附件下载仅本人收件。
 */
public interface InboxPort {

    /**
     * Insert one message, optional attachments, and fan-out entries in one transaction (N ≤ sync max).
     * 同事务插入一条消息、可选附件并同步写扩散收件行（N ≤ 同步上限）.
     */
    InboxSendResult send(InboxSendRequest request);

    /**
     * Same TX: message + attachments + fan-out job + staged recipients (no inbox_entry yet).
     * Requires {@code SYNC_FANOUT_MAX < unique N ≤ HARD_FANOUT_MAX}. Returns {@link InboxFanoutStatus#PENDING}.
     * 同事务写消息+附件+任务+暂存收件人（尚不写 inbox_entry）；返回 PENDING。
     */
    InboxSendResult sendDeferred(InboxSendRequest request);

    /**
     * Write up to {@code batchSize} {@code inbox_entry} rows for a fan-out job; drop staged recipients.
     * Returns entries written this call; {@code 0} means job done / missing / empty.
     * 为异步任务写入最多 batchSize 条 inbox_entry；返回本批写入数，0 表示已完成或无任务。
     */
    int processFanoutBatch(String jobId, int batchSize);

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

    /**
     * List attachments for a message reachable via own entry — 经本人收件可见的消息附件列表.
     * Empty if entry missing / not owned (fail-closed).
     */
    List<InboxAttachment> listAttachmentsForOwnEntry(
            String tenantId, InboxRecipientRealm realm, String recipientSubjectId, String entryId);

    /**
     * One attachment if own entry owns its message — 本人收件所属消息上的单个附件.
     */
    Optional<InboxAttachment> findAttachmentForOwnEntry(
            String tenantId,
            InboxRecipientRealm realm,
            String recipientSubjectId,
            String entryId,
            String attachmentId);
}
