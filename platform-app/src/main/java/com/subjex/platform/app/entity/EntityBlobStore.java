package com.subjex.platform.app.entity;

import java.util.List;
import java.util.Optional;

/**
 * EntityBlobStore — 实体附件元数据 + ObjectStorage 字节（ES-2 / ADR 0002）。
 * <p>
 * Keeps large / binary payloads out of hybrid {@code attrs}. Metadata rows live in {@code entity_blob};
 * bytes go through {@link com.subjex.platform.contract.storage.ObjectStorage}.
 * 大/二进制载荷不进 attrs；元数据在 entity_blob，字节走 ObjectStorage。
 */
public interface EntityBlobStore {

    /**
     * Store bytes + metadata; returns metadata with generated blobId —
     * 存字节并写元数据；返回含生成 blobId 的元数据。
     *
     * @param tenantId row tenant (empty string = platform sentinel for non-scoped); mapped for ObjectStorage
     */
    EntityBlobMetadata put(
            String tenantId,
            String entityKey,
            String recordId,
            String fieldName,
            byte[] content,
            String contentType);

    /** Metadata by blob id (tenant-scoped) — 按 blobId 读元数据（租户隔离）。 */
    Optional<EntityBlobMetadata> findMetadata(String tenantId, String blobId);

    /** Bytes via ObjectStorage using metadata storage_key — 经元数据 storage_key 取字节。 */
    Optional<byte[]> findContent(String tenantId, String blobId);

    /** List metadata for one entity record — 列出某实体记录的附件元数据。 */
    List<EntityBlobMetadata> listForRecord(String tenantId, String entityKey, String recordId);

    /**
     * Delete metadata + object bytes; returns whether a row existed —
     * 删元数据与对象字节；返回是否曾存在。
     */
    boolean delete(String tenantId, String blobId);

    /** Delete all blobs for a record (cascade helper) — 删除某记录全部附件（级联辅助）。 */
    int deleteForRecord(String tenantId, String entityKey, String recordId);
}
