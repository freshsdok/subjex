package com.subjex.platform.app.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * ChunkedBlobUploadSession — 分片上传会话元数据（CHUNK-1 MVP）。
 * <p>
 * Tracks expected total size and which chunk indices have been received.
 * 记录期望总大小与已收分片下标。
 */
public record ChunkedBlobUploadSession(
        String uploadId,
        String blobTenant,
        String entityKey,
        String recordId,
        String fieldName,
        String contentType,
        long totalBytes,
        int chunkSize,
        int chunkCount,
        Set<Integer> receivedIndices,
        Instant createdAt) {

    public ChunkedBlobUploadSession {
        Objects.requireNonNull(uploadId, "uploadId");
        Objects.requireNonNull(blobTenant, "blobTenant");
        Objects.requireNonNull(entityKey, "entityKey");
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(fieldName, "fieldName");
        Objects.requireNonNull(contentType, "contentType");
        receivedIndices = Set.copyOf(Objects.requireNonNull(receivedIndices, "receivedIndices"));
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** True when every index {@code 0..chunkCount-1} is present — 全部分片已齐。 */
    public boolean isComplete() {
        if (chunkCount <= 0) {
            return totalBytes == 0;
        }
        for (int i = 0; i < chunkCount; i++) {
            if (!receivedIndices.contains(i)) {
                return false;
            }
        }
        return true;
    }
}
