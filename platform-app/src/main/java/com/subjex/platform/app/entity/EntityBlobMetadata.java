package com.subjex.platform.app.entity;

import java.time.Instant;

/**
 * EntityBlobMetadata — 实体附件/blob 元数据行（ES-2）：指向 ObjectStorage，不装字节。
 * <p>
 * {@code storageKey} is the object key under the storage tenant namespace. Attrs may hold only a
 * pointer (e.g. blobId), never the payload bytes.
 * {@code storageKey} 是对象存储中的键；attrs 只可存指针（如 blobId），禁止存载荷字节。
 */
public record EntityBlobMetadata(
        String blobId,
        String tenantId,
        String entityKey,
        String recordId,
        String fieldName,
        String contentType,
        int byteSize,
        String storageKey,
        String checksumSha256,
        Instant createdAt) {}
