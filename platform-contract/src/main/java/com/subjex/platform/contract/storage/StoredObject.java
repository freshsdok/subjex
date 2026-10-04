package com.subjex.platform.contract.storage;

/**
 * StoredObject — 已存放对象：某个租户名下的一枚对象键、其字节和媒体类型。
 * <p>
 * The key is an object name, not a file-system path supplied by the caller.
 * 对象键是名字，不是调用方传入的文件系统路径。
 */
public record StoredObject(String tenantId, String objectKey, byte[] content, String mediaType) {
}
