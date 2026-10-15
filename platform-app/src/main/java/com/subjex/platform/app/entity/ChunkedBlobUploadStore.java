package com.subjex.platform.app.entity;

import java.util.Optional;

/**
 * ChunkedBlobUploadStore — 分片上传会话：init / putChunk / assemble / abort（CHUNK-1）。
 * <p>
 * Assembled bytes are returned to the caller for {@link EntityBlobStore#put}; this store only
 * holds temporary chunk files (local temp dir). Not S3 multipart protocol (optional later).
 * 组装后的字节交给 EntityBlobStore.put；本存储只持临时分片。不做 S3 multipart 协议。
 */
public interface ChunkedBlobUploadStore {

    /** Default advised chunk size (256 KiB) — 建议分片大小。 */
    int DEFAULT_CHUNK_SIZE = 256 * 1024;

    /** Session TTL before stale cleanup — 过期清理时限。 */
    long SESSION_TTL_SECONDS = 3600;

    /**
     * Start a session; {@code totalBytes} must be {@code 0..maxBytes} —
     * 开启会话；totalBytes 须在 0..maxBytes。
     */
    ChunkedBlobUploadSession init(
            String blobTenant,
            String entityKey,
            String recordId,
            String fieldName,
            String contentType,
            long totalBytes,
            int maxBytes);

    /** Session by id — 按 uploadId 取会话。 */
    Optional<ChunkedBlobUploadSession> find(String uploadId);

    /**
     * Store one chunk (idempotent overwrite for same index) —
     * 写入一分片（同下标可重试覆盖）。
     */
    ChunkedBlobUploadSession putChunk(String uploadId, int chunkIndex, byte[] data);

    /**
     * Assemble all chunks in order; does not delete session (caller aborts after put) —
     * 按序组装；不删会话（调用方 put 成功后再 abort）。
     */
    byte[] assemble(String uploadId);

    /** Delete session + chunk files — 删除会话与分片文件。 */
    void abort(String uploadId);

    /** Remove sessions older than TTL — 清理过期会话。 */
    int purgeExpired();
}
