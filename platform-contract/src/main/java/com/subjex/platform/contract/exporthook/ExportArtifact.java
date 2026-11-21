package com.subjex.platform.contract.exporthook;

import java.util.Objects;

/**
 * ExportArtifact — bytes plus content type produced by a {@link SubjectRecordExporter}.
 * 导出器产出的字节与内容类型。
 *
 * @param content bundle bytes — 包字节
 * @param contentType media type (e.g. application/json) — 媒体类型
 * @param fileName suggested file name — 建议文件名
 */
public record ExportArtifact(byte[] content, String contentType, String fileName) {

    public ExportArtifact {
        Objects.requireNonNull(content, "content");
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("contentType is required");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName is required");
        }
    }
}
