package com.subjex.platform.app.storage;

/**
 * ObjectDownloadDeniedException — generic denial when a signed download cannot be issued (WP3-2).
 * 无法签发签名下载时的通用拒绝（不向未授权调用方泄露对象是否存在）。
 */
public final class ObjectDownloadDeniedException extends RuntimeException {

    /** Stable HTTP reason — 稳定的 HTTP 原因码。 */
    public static final String REASON = "object-access-denied";

    public ObjectDownloadDeniedException() {
        super(REASON);
    }
}
