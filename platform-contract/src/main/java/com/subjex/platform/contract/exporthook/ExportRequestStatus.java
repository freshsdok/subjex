package com.subjex.platform.contract.exporthook;

/**
 * ExportRequestStatus — request lifecycle. READY / EXPIRED are reserved for WP8-3.
 * 导出申请生命周期。READY / EXPIRED 留给 WP8-3。
 */
public enum ExportRequestStatus {
    REQUESTED,
    APPROVED,
    REJECTED,
    READY,
    EXPIRED
}
