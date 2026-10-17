package com.subjex.platform.contract.inbox;

import java.util.Objects;

/**
 * InboxAttachmentBind — 发送时绑定的附件草稿（已上传到 ObjectStorage 的 staging/final key）。
 * <p>
 * Used by {@link InboxSendRequest}; persisted as {@link InboxAttachment} in the same send TX.
 * 供 {@link InboxSendRequest} 使用；同发送事务落成 {@link InboxAttachment}。
 *
 * @param fileName display file name — 展示文件名
 * @param contentType MIME type — MIME 类型
 * @param byteSize size in bytes — 字节数
 * @param storageKey ObjectStorage key already written — 已写入的对象键
 * @param checksumSha256 hex SHA-256 — 十六进制 SHA-256
 * @param sortOrder display order — 展示顺序
 */
public record InboxAttachmentBind(
        String fileName,
        String contentType,
        int byteSize,
        String storageKey,
        String checksumSha256,
        int sortOrder) {

    /** Max attachments per message (ADR 0009) — 每条消息附件上限. */
    public static final int MAX_PER_MESSAGE = 5;

    /** Max bytes per file; aligns entity blob MVP — 单文件上限，对齐实体附件 MVP. */
    public static final int MAX_BYTES = 5 * 1024 * 1024;

    public InboxAttachmentBind {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(storageKey, "storageKey");
        Objects.requireNonNull(checksumSha256, "checksumSha256");
        fileName = fileName.trim();
        contentType = contentType.trim();
        storageKey = storageKey.trim();
        checksumSha256 = checksumSha256.trim().toLowerCase();
        if (fileName.isEmpty()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        if (contentType.isEmpty()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        if (storageKey.isEmpty()) {
            throw new IllegalArgumentException("storageKey must not be blank");
        }
        if (checksumSha256.isEmpty()) {
            throw new IllegalArgumentException("checksumSha256 must not be blank");
        }
        if (byteSize < 1 || byteSize > MAX_BYTES) {
            throw new IllegalArgumentException("byteSize must be 1.." + MAX_BYTES);
        }
    }
}
