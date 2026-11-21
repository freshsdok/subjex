package com.subjex.platform.app.storage;

/**
 * ObjectDownloadInvalidException — local signed URL is expired, tampered, or incomplete (WP3-2).
 * 本地签名 URL 过期、被篡改或不完整。
 */
public final class ObjectDownloadInvalidException extends RuntimeException {

    /** Stable HTTP reason — 稳定的 HTTP 原因码。 */
    public static final String REASON = "object-download-invalid";

    public ObjectDownloadInvalidException() {
        super(REASON);
    }
}
