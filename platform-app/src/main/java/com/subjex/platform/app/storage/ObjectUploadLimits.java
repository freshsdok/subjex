package com.subjex.platform.app.storage;

import org.springframework.util.unit.DataSize;

/**
 * ObjectUploadLimits — generic single-file upload cap (WP3-3 / ADR 0015 D4).
 * 通用单文件上传上限。默认至少 50 MiB；测试可注入更小值走同一校验/组装路径。
 * <p>
 * Config key: {@code platform.storage.upload.max-bytes} /
 * {@code PLATFORM_STORAGE_UPLOAD_MAX_BYTES} (Spring {@link DataSize}, default {@code 50MB}).
 * 配置键见上，默认 {@code 50MB}。
 */
public final class ObjectUploadLimits {

    /** Default and advertised production cap (50 MiB) — 默认且对外的生产上限。 */
    public static final int DEFAULT_MAX_BYTES = 50 * 1024 * 1024;

    private final int maxBytes;

    public ObjectUploadLimits(int maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("platform.storage.upload.max-bytes must be at least 1");
        }
        this.maxBytes = maxBytes;
    }

    public static ObjectUploadLimits defaults() {
        return new ObjectUploadLimits(DEFAULT_MAX_BYTES);
    }

    public int maxBytes() {
        return maxBytes;
    }

    /**
     * Parse a Spring DataSize or raw byte count. Blank uses {@code fallback}.
     * 解析 Spring DataSize 或纯字节数；空白则用 fallback。
     */
    public static int parseMaxBytes(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            // Spring DataSize accepts MB/KB/GB, not MiB; accept both spellings.
            // Spring DataSize 认 MB/KB/GB，同时接受 MiB 写法。
            String value = raw.trim().replaceAll("(?i)mib$", "MB").replaceAll("(?i)kib$", "KB").replaceAll("(?i)gib$", "GB");
            long bytes = DataSize.parse(value).toBytes();
            if (bytes < 1 || bytes > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "platform.storage.upload.max-bytes is out of range: " + raw);
            }
            return (int) bytes;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(
                    "platform.storage.upload.max-bytes must be a size such as 50MB, got: " + raw, ex);
        }
    }

    @Override
    public String toString() {
        return "ObjectUploadLimits{maxBytes=" + maxBytes + "}";
    }
}
