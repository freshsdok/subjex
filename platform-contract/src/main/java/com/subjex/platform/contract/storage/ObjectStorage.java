package com.subjex.platform.contract.storage;

import java.util.Optional;

/**
 * ObjectStorage — 对象存储 SPI：放入与取回一个租户对象的唯一接口。
 * <p>
 * Compile the implementation into the process and register it. Do not add a storage console.
 * 把实现编译进进程并注册。不要加存储控制台。
 */
public interface ObjectStorage {

    void put(String tenantId, String objectKey, byte[] content, String mediaType);

    Optional<StoredObject> find(String tenantId, String objectKey);
}
