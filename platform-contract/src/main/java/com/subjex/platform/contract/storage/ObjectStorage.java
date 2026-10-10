package com.subjex.platform.contract.storage;

import java.util.Optional;

/**
 * ObjectStorage — 对象存储 SPI：放入 / 取回 / 删除一个租户对象。
 * <p>
 * Compile the implementation into the process and register it. Do not add a storage console.
 * Used by entity blob metadata (ES-2 / ADR 0002) so large payloads stay out of {@code attrs}.
 * 把实现编译进进程并注册。不要加存储控制台。实体 blob 元数据（ES-2）用此存放大载荷，勿写入 attrs。
 */
public interface ObjectStorage {

    void put(String tenantId, String objectKey, byte[] content, String mediaType);

    Optional<StoredObject> find(String tenantId, String objectKey);

    /**
     * Remove one object if present (no-op when missing) — 删除对象；不存在则无操作。
     */
    void delete(String tenantId, String objectKey);
}
