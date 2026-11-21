package com.subjex.platform.contract.exporthook;

/**
 * ExportNotApprovedException — fail-closed when an export is not in {@code APPROVED} status.
 * 导出申请并非已批准时的失败关闭异常（WP8-3 生成前的门禁）。
 */
public final class ExportNotApprovedException extends RuntimeException {

    /** Stable reason — 稳定原因. */
    public static final String REASON = "export-not-approved";

    public ExportNotApprovedException() {
        super(REASON);
    }
}
