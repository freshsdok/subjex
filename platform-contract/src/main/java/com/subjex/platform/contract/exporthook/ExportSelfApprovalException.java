package com.subjex.platform.contract.exporthook;

/**
 * ExportSelfApprovalException — the requester cannot approve or reject their own request.
 * 申请人不得批准或驳回自己的申请。
 */
public final class ExportSelfApprovalException extends RuntimeException {

    /** Stable reason — 稳定原因. */
    public static final String REASON = "export-self-approval-forbidden";

    public ExportSelfApprovalException() {
        super(REASON);
    }
}
