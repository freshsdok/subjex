package com.subjex.platform.app.storage;

/**
 * ObjectUploadRejectedException — end-user (or shared) upload rejected for size or MIME.
 * 上传因大小或 MIME 被拒绝，映射为 413 / 415。
 */
public final class ObjectUploadRejectedException extends RuntimeException {

    /** Oversize reason — 超限原因. */
    public static final String TOO_LARGE = "object-upload-too-large";

    /** Disallowed MIME reason — 不允许的媒体类型. */
    public static final String CONTENT_TYPE = "object-content-type-not-allowed";

    private final int status;
    private final String reason;

    private ObjectUploadRejectedException(int status, String reason, String message) {
        super(message);
        this.status = status;
        this.reason = reason;
    }

    /** Content longer than the configured cap — 内容超过配置上限. */
    public static ObjectUploadRejectedException tooLarge(int maxBytes) {
        return new ObjectUploadRejectedException(
                413, TOO_LARGE, "content exceeds maxBytes=" + maxBytes);
    }

    /** MIME not on {@link ObjectContentTypes} — 不在共用 MIME 白名单. */
    public static ObjectUploadRejectedException contentType(String contentType) {
        return new ObjectUploadRejectedException(
                415, CONTENT_TYPE, "contentType not allowed: " + (contentType == null ? "" : contentType));
    }

    public int status() {
        return status;
    }

    public String reason() {
        return reason;
    }
}
