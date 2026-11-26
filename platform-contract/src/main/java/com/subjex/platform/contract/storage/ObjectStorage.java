package com.subjex.platform.contract.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Optional;

/**
 * ObjectStorage — 对象存储 SPI：放入 / 取回 / 删除一个租户对象。
 * <p>
 * Compile the implementation into the process and register it. Do not add a storage console.
 * Used by entity blob metadata (ES-2 / ADR 0002) so large payloads stay out of {@code attrs}.
 * {@link #put(String, String, String, ObjectPutWriter)} streams a payload without requiring the
 * caller to hold the whole object in a byte array; the default implementation buffers only when
 * a store has not overridden it.
 * 把实现编译进进程并注册。不要加存储控制台。实体 blob 元数据（ES-2）用此存放大载荷，勿写入 attrs。
 * {@link #put(String, String, String, ObjectPutWriter)} 以流方式写入，调用方不必先把整包放进字节数组；
 * 若实现未覆盖，默认实现才会在内存中缓冲。
 */
public interface ObjectStorage {

    void put(String tenantId, String objectKey, byte[] content, String mediaType);

    /**
     * Stream a payload into storage. Preferred for large export artifacts.
     * 以流方式写入存储。大型导出产物应走这条路径。
     */
    default void put(String tenantId, String objectKey, String mediaType, ObjectPutWriter writer) {
        if (writer == null) {
            throw new IllegalArgumentException("writer is required");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            writer.writeTo(buffer);
        } catch (IOException ex) {
            throw new UncheckedIOException("could not buffer object " + objectKey, ex);
        }
        put(tenantId, objectKey, buffer.toByteArray(), mediaType);
    }

    Optional<StoredObject> find(String tenantId, String objectKey);

    /**
     * Remove one object if present (no-op when missing) — 删除对象；不存在则无操作。
     */
    void delete(String tenantId, String objectKey);

    /**
     * Writes an object body to a stream supplied by the store — 向存储提供的流写入对象正文.
     */
    @FunctionalInterface
    interface ObjectPutWriter {
        void writeTo(OutputStream output) throws IOException;
    }
}
