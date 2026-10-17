package com.subjex.platform.app.inbox;

import java.time.Instant;
import java.util.Objects;

/**
 * InboxAttachmentStaging — 发送前暂存附件（已写入 ObjectStorage，待 bind 到消息）。
 * <p>
 * Consumed once by system send; TTL purge drops metadata (and may delete object).
 * 系统发送时一次性消费；过期清理元数据（并可删对象）。
 *
 * @param stagingId staging id returned to client — 返回给客户端的暂存 id
 * @param tenantId owning tenant — 所属租户
 * @param uploaderSubjectId who uploaded (must match sender) — 上传者（须与发送者一致）
 * @param fileName display name — 展示文件名
 * @param contentType MIME type — MIME
 * @param byteSize size — 字节数
 * @param storageKey ObjectStorage key — 对象键
 * @param checksumSha256 hex SHA-256 — 校验和
 * @param createdAt created — 创建时间
 * @param expiresAt expiry — 过期时间
 */
public record InboxAttachmentStaging(
        String stagingId,
        String tenantId,
        String uploaderSubjectId,
        String fileName,
        String contentType,
        int byteSize,
        String storageKey,
        String checksumSha256,
        Instant createdAt,
        Instant expiresAt) {

    public InboxAttachmentStaging {
        Objects.requireNonNull(stagingId, "stagingId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(uploaderSubjectId, "uploaderSubjectId");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(storageKey, "storageKey");
        Objects.requireNonNull(checksumSha256, "checksumSha256");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public boolean expiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
