package com.subjex.platform.contract.inbox;

import java.time.Instant;
import java.util.Objects;

/**
 * InboxAttachment — 站内信附件元数据（字节在 ObjectStorage，不进 body / entity_blob）。
 * <p>
 * Metadata only; bytes live under {@code storageKey} via ObjectStorage (ADR 0009).
 * 仅元数据；字节经 ObjectStorage 的 storageKey 外置（ADR 0009）。
 *
 * @param attachmentId primary key — 主键
 * @param messageId owning message — 所属消息
 * @param tenantId owning tenant — 所属租户
 * @param fileName display file name — 展示文件名
 * @param contentType MIME type — MIME 类型
 * @param byteSize size in bytes — 字节数
 * @param storageKey ObjectStorage key — 对象存储键
 * @param checksumSha256 hex SHA-256 — 十六进制 SHA-256
 * @param sortOrder display order — 展示顺序
 * @param createdAt created instant — 创建时间
 */
public record InboxAttachment(
        String attachmentId,
        String messageId,
        String tenantId,
        String fileName,
        String contentType,
        int byteSize,
        String storageKey,
        String checksumSha256,
        int sortOrder,
        Instant createdAt) {

    public InboxAttachment {
        Objects.requireNonNull(attachmentId, "attachmentId");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(storageKey, "storageKey");
        Objects.requireNonNull(checksumSha256, "checksumSha256");
        Objects.requireNonNull(createdAt, "createdAt");
        if (byteSize < 0) {
            throw new IllegalArgumentException("byteSize must be >= 0");
        }
    }
}
